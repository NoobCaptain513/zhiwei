package com.zihan.zhiwei.ai.provider.failover;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import com.zihan.zhiwei.ai.provider.ProviderMetrics;
import com.zihan.zhiwei.ai.provider.ProviderUtils;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import com.zihan.zhiwei.ai.provider.health.FailoverEventLog;
import com.zihan.zhiwei.ai.provider.probe.FirstPacketProbeConfig;
import com.zihan.zhiwei.ai.provider.probe.ModelProbeService;
import com.zihan.zhiwei.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D8: 故障降级
 * <p>
 * FIX-3: 重试前先经 ProviderUtils.isRetryableOnSameProvider() 判定异常类别。
 */
@Slf4j
@Component
public class FailoverHandler {

    private static final List<String> DEFAULT_FAILOVER_CHAIN = List.of(
            "spring-ai-alibaba",
            "langchain4j-openai",
            "native-dashscope"
    );

    private final Map<String, ModelProvider> providerMap;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ProviderMetrics providerMetrics;
    private final List<String> failoverChain;
    private final boolean retryEnabled;
    private final int maxAttempts;
    private final FailoverEventLog failoverEventLog;
    private final ModelProbeService probeService;
    private final int probeCandidateLimit;

    public FailoverHandler(
            List<ModelProvider> providers,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ProviderMetrics providerMetrics,
            Environment environment,
            @Value("${zhiwei.ai.router.retry.enabled:true}") boolean retryEnabled,
            @Value("${zhiwei.ai.router.retry.max-attempts:1}") int maxAttempts,
            FailoverEventLog failoverEventLog,
            ModelProbeService probeService,
            FirstPacketProbeConfig probeConfig) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::name, Function.identity(), (a, b) -> a));
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.providerMetrics = providerMetrics;
        this.failoverChain = Binder.get(environment)
                .bind("zhiwei.ai.router.failover-chain", Bindable.listOf(String.class))
                .orElse(DEFAULT_FAILOVER_CHAIN);
        this.retryEnabled = retryEnabled;
        this.maxAttempts = Math.max(0, maxAttempts);
        this.probeCandidateLimit = Math.max(1, probeConfig.getCandidateLimit());
        log.info("[Failover] chain={}", this.failoverChain);
        this.failoverEventLog = failoverEventLog;
        this.probeService = probeService;
    }

    public FailoverResult execute(String primaryProvider, ProviderChatRequest request) {
        List<String> chain = buildChain(primaryProvider);

        // D28: 首包探测
        Set<String> probeDead = preProbe(chain);

        List<FailoverEvent> events = new ArrayList<>();
        Exception lastError = null;

        for (int i = 0; i < chain.size(); i++) {
            String name = chain.get(i);
            ModelProvider provider = providerMap.get(name);
            if (provider == null || !provider.isAvailable()) {
                log.warn("[Failover] skip unavailable provider={}", name);
                continue;
            }

            CircuitBreaker cb;
            try {
                cb = circuitBreakerRegistry.circuitBreaker(name);
            } catch (Exception e) {
                log.warn("[Failover] circuit breaker error for provider={}: {}, skip", name, e.getMessage());
                continue;
            }
            if (cb.getState() == CircuitBreaker.State.OPEN) {
                log.warn("[Failover] circuit OPEN, skip provider={}", name);
                if (i + 1 < chain.size()) {
                    events.add(FailoverEvent.of(name, chain.get(i + 1), "CIRCUIT_OPEN"));
                }
                continue;
            }

            if (probeDead.contains(name)) {
                log.warn("[Failover] probe dead, skip provider={}", name);
                if (i + 1 < chain.size()) {
                    events.add(FailoverEvent.of(name, chain.get(i + 1), "PROBE_DEAD"));
                }
                continue;
            }

            int attempts = 1 + (retryEnabled ? maxAttempts : 0);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                long start = System.currentTimeMillis();
                try {
                    var response = cb.executeSupplier(() -> provider.chat(request));
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordSuccess(name, latency);

                    boolean degraded = !name.equals(primaryProvider);
                    if (degraded && !events.isEmpty()) {
                        for (FailoverEvent evt : events) {
                            failoverEventLog.record(evt);
                        }
                    }
                    return new FailoverResult(response, primaryProvider, name, degraded, latency, List.copyOf(events));
                } catch (CallNotPermittedException e) {
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordFailure(name, latency);
                    lastError = e;
                    log.warn("[Failover] call not permitted provider={} state={}", name, cb.getState());
                    break;
                } catch (Exception e) {
                    long latency = System.currentTimeMillis() - start;
                    providerMetrics.recordFailure(name, latency);
                    lastError = e;

                    // FIX-3: 确定性失败不在同 Provider 重试
                    boolean retryable = ProviderUtils.isRetryableOnSameProvider(e);
                    log.warn("[Failover] provider={} attempt={}/{} failed retryable={}: {}",
                            name, attempt, attempts, retryable, e.getMessage());

                    if (retryable && attempt < attempts) {
                        continue;
                    }
                    if (i + 1 < chain.size()) {
                        String reason = (retryable ? "" : "NON_RETRYABLE ")
                                + e.getClass().getSimpleName() + ": " + safeMsg(e);
                        events.add(FailoverEvent.of(name, chain.get(i + 1), reason));
                    }
                    break;
                }
            }
        }

        throw new BusinessException("全部 Provider 调用失败: "
                + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    public CircuitBreaker.State stateOf(String provider) {
        try {
            return circuitBreakerRegistry.circuitBreaker(provider).getState();
        } catch (Exception e) {
            log.debug("[Failover] circuit breaker error for provider={}: {}, treating as CLOSED", provider, e.getMessage());
            return CircuitBreaker.State.CLOSED;
        }
    }

    public boolean isCircuitOpen(String provider) {
        return stateOf(provider) == CircuitBreaker.State.OPEN;
    }

    private Set<String> preProbe(List<String> chain) {
        if (!probeService.isEnabled()) {
            return Collections.emptySet();
        }
        List<ModelProvider> candidates = chain.stream()
                .map(providerMap::get)
                .filter(Objects::nonNull)
                .filter(ModelProvider::isAvailable)
                .limit(probeCandidateLimit)
                .collect(Collectors.toCollection(ArrayList::new));

        if (candidates.isEmpty()) {
            return Collections.emptySet();
        }

        List<ModelProvider> healthy = probeService.filterAvailable(candidates, probeCandidateLimit);
        return candidates.stream()
                .filter(p -> !healthy.contains(p))
                .map(ModelProvider::name)
                .collect(Collectors.toSet());
    }

    private List<String> buildChain(String primary) {
        List<String> chain = new ArrayList<>();
        if (primary != null && !primary.isBlank()) {
            chain.add(primary);
        }
        for (String name : failoverChain) {
            if (!chain.contains(name)) {
                chain.add(name);
            }
        }
        return chain;
    }

    private static String safeMsg(Exception e) {
        return ProviderUtils.safeMsg(e);
    }
}
