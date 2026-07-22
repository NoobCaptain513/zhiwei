package com.zihan.zhiwei.ai.provider.nativehttp;

import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * 成本校准拦截器：估算费用 + 写入成本权重到 Redis，供路由器打分。
 */
@Slf4j
@Component
public class CostCalibrationInterceptor {

    public static final String COST_WEIGHT_KEY_PREFIX = "zhiwei:provider:cost-weight:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${zhiwei.ai.cost.prompt-price-per-1k:0.004}")
    private double promptPricePer1k;

    @Value("${zhiwei.ai.cost.completion-price-per-1k:0.012}")
    private double completionPricePer1k;

    @Value("${zhiwei.ai.cost.baseline-per-request:0.02}")
    private double baselinePerRequest;

    public CostCalibrationInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public BigDecimal calibrate(ProviderChatResponse response) {
        // D23: Ollama 本地模型零成本
        if ("ollama".equals(response.provider())) {
            String key = COST_WEIGHT_KEY_PREFIX + response.provider();
            try {
                stringRedisTemplate.opsForValue().set(key, "0.100000", 1, TimeUnit.HOURS);
            } catch (Exception ex) {
                log.warn("写入 Ollama 成本权重失败: {}", ex.getMessage());
            }
            log.debug("cost calibrated provider=ollama, tokens={}, cost=0 (local)", response.totalTokens());
            return BigDecimal.ZERO;
        }

        BigDecimal estimated = estimateCost(response.promptTokens(), response.completionTokens());
        double weight = baselinePerRequest <= 0
                ? 1.0
                : estimated.doubleValue() / baselinePerRequest;
        weight = Math.max(0.1, Math.min(10.0, weight));

        String key = COST_WEIGHT_KEY_PREFIX + response.provider();
        try {
            stringRedisTemplate.opsForValue().set(key, String.format("%.6f", weight), 1, TimeUnit.HOURS);
        } catch (Exception ex) {
            log.warn("写入成本权重失败 provider={}, err={}", response.provider(), ex.getMessage());
        }
        log.debug("cost calibrated provider={}, tokens={}, cost={}, weight={}",
                response.provider(), response.totalTokens(), estimated, weight);
        return estimated;
    }

    public BigDecimal estimateCost(int promptTokens, int completionTokens) {
        BigDecimal prompt = BigDecimal.valueOf(promptTokens)
                .multiply(BigDecimal.valueOf(promptPricePer1k))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        BigDecimal completion = BigDecimal.valueOf(completionTokens)
                .multiply(BigDecimal.valueOf(completionPricePer1k))
                .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP);
        return prompt.add(completion).setScale(6, RoundingMode.HALF_UP);
    }

    public double readWeight(String provider) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(COST_WEIGHT_KEY_PREFIX + provider);
            if (raw != null && !raw.isBlank()) {
                return Double.parseDouble(raw);
            }
        } catch (Exception ex) {
            log.debug("读取成本权重失败 provider={}, err={}", provider, ex.getMessage());
        }
        return 1.0;
    }
}