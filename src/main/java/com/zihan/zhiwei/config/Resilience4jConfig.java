package com.zihan.zhiwei.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class Resilience4jConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            @Value("${zhiwei.ai.router.circuit-breaker.failure-rate-threshold:50}") float failureRate,
            @Value("${zhiwei.ai.router.circuit-breaker.slow-call-duration-ms:5000}") long slowCallMs,
            @Value("${zhiwei.ai.router.circuit-breaker.slow-call-rate-threshold:80}") float slowCallRate,
            @Value("${zhiwei.ai.router.circuit-breaker.wait-duration-in-open-ms:30000}") long waitOpenMs,
            @Value("${zhiwei.ai.router.circuit-breaker.sliding-window-size:20}") int windowSize,
            @Value("${zhiwei.ai.router.circuit-breaker.minimum-number-of-calls:5}") int minCalls) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRate)
                .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
                .slowCallRateThreshold(slowCallRate)
                .waitDurationInOpenState(Duration.ofMillis(waitOpenMs))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minCalls)
                .build();

        return CircuitBreakerRegistry.of(config);
    }
}