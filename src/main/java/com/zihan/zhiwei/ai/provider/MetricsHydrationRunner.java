package com.zihan.zhiwei.ai.provider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FIX-7: 应用就绪后，从 Redis 读回各 Provider 的历史指标样本，
 * 回放进 ProviderMetrics 内存窗口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsHydrationRunner {

    private final List<ModelProvider> providers;
    private final ProviderMetrics providerMetrics;
    private final ObjectProvider<MetricsPersistence> persistenceProvider;

    @EventListener(ApplicationReadyEvent.class)
    public void hydrateOnStartup() {
        MetricsPersistence persistence = persistenceProvider.getIfAvailable();
        if (persistence == null) {
            log.info("[Metrics] no persistence available, skip hydration");
            return;
        }
        for (ModelProvider provider : providers) {
            try {
                providerMetrics.hydrate(provider.name(), persistence.load(provider.name()));
            } catch (Exception e) {
                log.warn("[Metrics] hydrate failed provider={}: {}", provider.name(), e.getMessage());
            }
        }
    }
}
