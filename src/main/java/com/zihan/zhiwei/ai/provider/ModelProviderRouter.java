package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverEvent;
import com.zihan.zhiwei.ai.provider.failover.FailoverHandler;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
import com.zihan.zhiwei.ai.provider.probe.ModelProbeService;
import com.zihan.zhiwei.ai.stream.StreamResult;
import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D7+D15: Provider 路由器。
 * 按健康度 + 成功率 + 延迟 + 成本权重打分选出主 Provider，再经 FailoverHandler 降级执行。
 * D15: 新增 streamChatWithFailover() 支持流式降级。
 * D28: 流式路由集成首包探测，在 SSE 启动前过滤不可用 Provider。
 * <p>
 * FIX-2: 流式指标修正——TTFT + 完整失败记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelProviderRouter {

    private final List<ModelProvider> providers;
    private final ProviderMetrics providerMetrics;
    private final FailoverHandler failoverHandler;
    private final HealthMonitor healthMonitor;
    private final CostCalibrationInterceptor costCalibrationInterceptor;
    private final ModelProbeService probeService;
    private final FailoverEventLog failoverEventLog;

    @Value("${zhiwei.ai.default-provider:spring-ai-alibaba}")
    private String defaultProvider;

    @Value("${zhiwei.ai.router.latency-penalty-ms:2000}")
    private long latencyPenaltyMs;

    // ==================== 同步路由 ====================

    public ModelProvider route() {
        return route(defaultProvider);
    }

    public ModelProvider route(String preferred) {
        List<ModelProvider> ranked = rankCandidates(preferred);
        return route(ranked);
    }

    /**
     * Select the primary provider from an already score-ranked candidate list.
     */
    private ModelProvider route(List<ModelProvider> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            throw new BusinessException("没有可用的 Provider");
        }
        return ranked.get(0);
    }

    public FailoverResult executeWithFailover(ProviderChatRequest request) {
        return executeWithFailover(defaultProvider, request);
    }

    public FailoverResult executeWithFailover(String preferred, ProviderChatRequest request) {
        List<ModelProvider> ranked = rankCandidates(preferred);
        if (ranked.isEmpty()) {
            throw new BusinessException("没有可用的 Provider");
        }

        // Sync and streaming paths share the same score-ranked candidates.
        ModelProvider primary = route(ranked);
        FailoverResult result = failoverHandler.execute(primary.name(), request, ranked);
        if (result.degraded()) {
            log.info("[Router] chat degraded primary={} actual={} events={}",
                    result.primaryProvider(), result.actualProvider(), result.events().size());
        }
        return result;
    }

    public ProviderChatResponse chatWithFailover(ProviderChatRequest request) {
        return executeWithFailover(request).response();
    }

    public ProviderChatResponse chatWithFailover(String preferred, ProviderChatRequest request) {
        return executeWithFailover(preferred, request).response();
    }

    // ==================== D15+D28+FIX-2: 流式路由 ====================

    public StreamResult streamChatWithFailover(ProviderChatRequest request, Consumer<String> onToken) {
        return streamChatWithFailover(defaultProvider, request, onToken);
    }

    public StreamResult streamChatWithFailover(String preferred, ProviderChatRequest request,
                                                Consumer<String> onToken) {
        List<ModelProvider> ranked = rankCandidates(preferred);
        if (ranked.isEmpty()) {
            throw new BusinessException("没有可用的 Provider");
        }

        // D28: 首包探测过滤
        List<ModelProvider> filtered = probeService.filterAvailable(ranked);
        for (int i = 0; i < ranked.size(); i++) {
            if (!filtered.contains(ranked.get(i))) {
                String from = ranked.get(i).name();
                String to = (i + 1 < ranked.size()) ? ranked.get(i + 1).name() : "none";
                failoverEventLog.record(FailoverEvent.of(from, to, "STREAM_PROBE_DEAD"));
            }
        }
        if (filtered.isEmpty()) {
            throw new BusinessException("全部 Provider 探测不可达");
        }
        log.info("[Router] streamChat probe: ranked={} afterProbe={}",
                ranked.size(), filtered.size());

        AtomicBoolean tokenSent = new AtomicBoolean(false);
        // FIX-2: 记录首 token 到达时刻，用于计算 TTFT
        AtomicLong firstTokenAt = new AtomicLong(-1L);
        Consumer<String> trackingOnToken = token -> {
            firstTokenAt.compareAndSet(-1L, System.currentTimeMillis());
            tokenSent.set(true);
            onToken.accept(token);
        };

        Exception lastError = null;
        for (int i = 0; i < filtered.size(); i++) {
            ModelProvider provider = filtered.get(i);
            long attemptStart = System.currentTimeMillis();
            firstTokenAt.set(-1L);
            try {
                StreamResult result = provider.streamChat(request, trackingOnToken);
                // FIX-2: 流式场景延迟口径 = TTFT
                long ttft = firstTokenAt.get() > 0
                        ? firstTokenAt.get() - attemptStart
                        : System.currentTimeMillis() - attemptStart;
                providerMetrics.recordSuccess(provider.name(), ttft);
                return result;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - attemptStart;
                // FIX-2: 无论中断发生在首 token 前还是后，都要计入失败样本
                providerMetrics.recordFailure(provider.name(), elapsed);
                if (tokenSent.get()) {
                    failoverEventLog.record(FailoverEvent.of(provider.name(), "none",
                            "STREAM_INTERRUPTED_AFTER_FIRST_TOKEN: "
                                    + e.getClass().getSimpleName() + ": " + safeMsg(e)));
                    throw new BusinessException("流式传输中断（" + provider.name() + "）: " + e.getMessage());
                }
                lastError = e;
                if (i + 1 < filtered.size()) {
                    failoverEventLog.record(FailoverEvent.of(provider.name(),
                            filtered.get(i + 1).name(),
                            "STREAM_FAILED: " + e.getClass().getSimpleName() + ": " + safeMsg(e)));
                }
                log.warn("[Router] streamChat failed provider={}, err={}", provider.name(), e.getMessage());
            }
        }
        throw new BusinessException("全部 Provider 流式调用失败: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    // ==================== 私有方法 ====================

    private List<ModelProvider> rankCandidates(String preferred) {
        Map<String, ModelProvider> providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::name, Function.identity(), (a, b) -> a));

        List<Scored> scored = new ArrayList<>();
        for (ModelProvider provider : providers) {
            if (!provider.isAvailable() || !healthMonitor.isHealthy(provider.name())) {
                continue;
            }
            scored.add(new Scored(provider, score(provider, preferred)));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<ModelProvider> ranked = scored.stream()
                .map(Scored::provider)
                .collect(Collectors.toCollection(ArrayList::new));

        if (ranked.isEmpty()) {
            ModelProvider fallback = providerMap.get(preferred);
            if (fallback != null) {
                ranked.add(fallback);
            } else if (!providers.isEmpty()) {
                ranked.addAll(providers);
            }
        }
        return ranked;
    }

    private double score(ModelProvider provider, String preferred) {
        ProviderMetrics.Snapshot snapshot = providerMetrics.snapshot(provider.name());
        double successScore = snapshot.successRate();
        double latencyScore = 1.0;
        if (snapshot.avgLatencyMs() > 0 && latencyPenaltyMs > 0) {
            latencyScore = Math.max(0.1, 1.0 - ((double) snapshot.avgLatencyMs() / (double) latencyPenaltyMs));
        }
        double costWeight = costCalibrationInterceptor.readWeight(provider.name());
        // Cost weight is calibrated to [0.1, 10.0]; map it to [0, 1]
        // before applying the configured cost coefficient.
        double boundedCostWeight = Math.max(0.1, Math.min(10.0, costWeight));
        double costScore = 1.0 - (boundedCostWeight - 0.1) / 9.9;
        double preferBonus = provider.name().equals(preferred) ? 0.15 : 0.0;
        return successScore * 0.45 + latencyScore * 0.25 + costScore * 0.15 + preferBonus;
    }

    private record Scored(ModelProvider provider, double score) {}

    private static String safeMsg(Exception e) {
        return ProviderUtils.safeMsg(e);
    }
}
