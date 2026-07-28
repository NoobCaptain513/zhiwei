package com.zihan.zhiwei.ai.provider;

import java.util.List;

/**
 * FIX-7: 指标样本持久化接口。
 * ProviderMetrics 通过它把每个样本旁路写出（best-effort），
 * 并在启动时读回历史样本做"水合"。
 */
public interface MetricsPersistence {

    /** 旁路写出一个样本（实现必须自行吞掉异常） */
    void push(String provider, boolean success, long latencyMs);

    /** 读回某 Provider 的历史样本，按时间从旧到新排序 */
    List<PersistedSample> load(String provider);

    record PersistedSample(boolean success, long latencyMs, long ts) {}
}
