package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.failover.FailoverHandler;
import com.zihan.zhiwei.ai.provider.failover.FailoverResult;
import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D7+D15: Provider 路由器。
 * 按健康度 + 成功率 + 延迟 + 成本权重打分选出主 Provider，再经 FailoverHandler 降级执行。
 * D15: 新增 streamChatWithFailover() 支持流式降级。
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

    @Value("${zhiwei.ai.default-provider:spring-ai-alibaba}")
    private String defaultProvider;

    @Value("${zhiwei.ai.router.latency-penalty-ms:2000}")
    private long latencyPenaltyMs;

    // ==================== 同步路由（保持不变）====================

    public ModelProvider route() {
        return route(defaultProvider);
    }

    public ModelProvider route(String preferred) {
        List<ModelProvider> ranked = rankCandidates(preferred);
        if (ranked.isEmpty()) {
            throw new BusinessException("没有可用的 Provider");
        }
        return ranked.get(0);
    }

    public FailoverResult executeWithFailover(ProviderChatRequest request) {
        return executeWithFailover(defaultProvider, request);
    }

    public FailoverResult executeWithFailover(String preferred, ProviderChatRequest request) {
        String primaryName = preferred != null ? preferred : defaultProvider;
        FailoverResult result = failoverHandler.execute(primaryName, request);
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

    // ==================== D15: 流式路由 ====================

    /**
     * D15: 流式路由（使用默认 Provider）。
     * 按打分排序依次尝试，首个 Provider 如果在发送任何 token 之前失败，则自动降级到下一个。
     * 一旦有 token 发出，失败不再降级（因为客户端已收到部分数据）。
     */
    public StreamResult streamChatWithFailover(ProviderChatRequest request, Consumer<String> onToken) {
        return streamChatWithFailover(defaultProvider, request, onToken);
    }

    public StreamResult streamChatWithFailover(String preferred, ProviderChatRequest request,
                                                Consumer<String> onToken) {
        List<ModelProvider> ranked = rankCandidates(preferred);
        if (ranked.isEmpty()) {
            throw new BusinessException("没有可用的 Provider");
        }

        AtomicBoolean tokenSent = new AtomicBoolean(false);
        Consumer<String> trackingOnToken = token -> {
            tokenSent.set(true);
            onToken.accept(token);
        };

        Exception lastError = null;
        for (ModelProvider provider : ranked) {
            try {
                StreamResult result = provider.streamChat(request, trackingOnToken);
                // 记录成功指标
                providerMetrics.recordSuccess(provider.name(), 0);
                return result;
            } catch (Exception e) {
                if (tokenSent.get()) {
                    // 已经有 token 发出，不能再降级，直接抛异常
                    throw new BusinessException("流式传输中断（" + provider.name() + "）: " + e.getMessage());
                }
                // 还没发 token，记录失败并尝试下一个
                providerMetrics.recordFailure(provider.name(), 0);
                lastError = e;
                log.warn("[Router] streamChat failed provider={}, err={}", provider.name(), e.getMessage());
            }
        }
        throw new BusinessException("全部 Provider 流式调用失败: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    // ==================== 私有方法（保持不变）====================

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
        double costScore = 1.0 / Math.max(0.1, costWeight);
        double preferBonus = provider.name().equals(preferred) ? 0.15 : 0.0;
        return successScore * 0.45 + latencyScore * 0.25 + costScore * 0.15 + preferBonus;
    }

    private record Scored(ModelProvider provider, double score) {}
}