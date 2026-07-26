package com.zihan.zhiwei.ai.provider.probe;

import com.zihan.zhiwei.ai.provider.ModelProvider;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 首包探测服务。
 * 在真正发起流式/同步调用前，用极简请求探测 Provider 可用性。
 * 结果带短 TTL 缓存，避免重复探测同一 Provider。
 */
@Slf4j
@Component
public class ModelProbeService {

    private final Map<String, ModelProvider> providerMap;
    private final Map<String, CachedProbe> cache = new ConcurrentHashMap<>();
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final FirstPacketProbeConfig config;

    public ModelProbeService(List<ModelProvider> providers, FirstPacketProbeConfig config) {
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(ModelProvider::name, Function.identity(), (a, b) -> a));
        this.config = config;
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * P3-24 修复：应用关闭时优雅关闭探测线程池。
     */
    @PreDestroy
    public void shutdown() {
        log.info("[Probe] shutting down probe executor...");
        probeExecutor.close();
    }

    /**
     * 探测单个 Provider，带缓存。
     */
    public ProbeResult probe(String providerName) {
        if (!config.isEnabled()) {
            return ProbeResult.ok(providerName, 0);
        }

        CachedProbe cached = cache.get(providerName);
        if (cached != null && !cached.expired(config.getCacheTtlMs())) {
            log.debug("[Probe] cache hit provider={} success={}", providerName, cached.result.success());
            return cached.result;
        }

        ModelProvider provider = providerMap.get(providerName);
        if (provider == null) {
            return ProbeResult.fail(providerName, 0, "Provider not found");
        }

        long start = System.currentTimeMillis();
        try {
            Future<ProbeResult> future = probeExecutor.submit(provider::probe);
            ProbeResult result = future.get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            cache.put(providerName, new CachedProbe(result, Instant.now()));
            log.debug("[Probe] done provider={} success={} latencyMs={}",
                    providerName, result.success(), result.latencyMs());
            return result;
        } catch (TimeoutException e) {
            ProbeResult result = ProbeResult.fail(providerName,
                    System.currentTimeMillis() - start, "timeout");
            cache.put(providerName, new CachedProbe(result, Instant.now()));
            log.warn("[Probe] timeout provider={} timeoutMs={}", providerName, config.getTimeoutMs());
            return result;
        } catch (Exception e) {
            ProbeResult result = ProbeResult.fail(providerName,
                    System.currentTimeMillis() - start, e.getMessage());
            cache.put(providerName, new CachedProbe(result, Instant.now()));
            log.warn("[Probe] failed provider={} err={}", providerName, e.getMessage());
            return result;
        }
    }

    /**
     * 读取缓存（不触发新探测）。过期或不存在返回 null。
     */
    public ProbeResult getCached(String providerName) {
        CachedProbe cached = cache.get(providerName);
        if (cached != null && !cached.expired(config.getCacheTtlMs())) {
            return cached.result;
        }
        return null;
    }

    /**
     * 并行探测前 N 个候选，返回健康的（保持原顺序）。
     * 未探测的候选直接追加到末尾作为兜底。
     */
    public List<ModelProvider> filterAvailable(List<ModelProvider> candidates) {
        return filterAvailable(candidates, config.getCandidateLimit());
    }

    public List<ModelProvider> filterAvailable(List<ModelProvider> candidates, int limit) {
        if (!config.isEnabled() || candidates.isEmpty()) {
            return new ArrayList<>(candidates);
        }

        int probeCount = Math.min(limit, candidates.size());
        List<ModelProvider> toProbe = candidates.subList(0, probeCount);

        List<CompletableFuture<ProbeResult>> futures = toProbe.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> probe(p.name()), probeExecutor))
                .toList();

        List<ModelProvider> healthy = new ArrayList<>();
        for (int i = 0; i < toProbe.size(); i++) {
            try {
                ProbeResult result = futures.get(i).get(config.getTimeoutMs() + 500, TimeUnit.MILLISECONDS);
                if (result.success()) {
                    healthy.add(toProbe.get(i));
                }
            } catch (Exception e) {
                log.warn("[Probe] filterAvailable error provider={} err={}",
                        toProbe.get(i).name(), e.getMessage());
            }
        }

        if (candidates.size() > probeCount) {
            for (int i = probeCount; i < candidates.size(); i++) {
                if (!healthy.contains(candidates.get(i))) {
                    healthy.add(candidates.get(i));
                }
            }
        }

        log.debug("[Probe] filterAvailable total={} probed={} healthy={}",
                candidates.size(), probeCount, healthy.size());
        return healthy;
    }

    // P2-16 修复：删除 invalidateCache() 死代码（全项目零调用方）

    private record CachedProbe(ProbeResult result, Instant cachedAt) {
        boolean expired(long ttlMs) {
            return Instant.now().isAfter(cachedAt.plusMillis(ttlMs));
        }
    }
}
