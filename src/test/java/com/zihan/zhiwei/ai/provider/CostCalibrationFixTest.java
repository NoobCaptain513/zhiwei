package com.zihan.zhiwei.ai.provider;

import com.zihan.zhiwei.ai.provider.nativehttp.CostCalibrationInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试成本计算修复：验证 Ollama 本地模型零成本
 */
@SpringBootTest
public class CostCalibrationFixTest {

    @Autowired
    private CostCalibrationInterceptor costCalibrationInterceptor;

    @Test
    public void testOllamaCostShouldBeZero() {
        // 测试1：Ollama Provider 应该返回零成本
        BigDecimal ollamaCost = costCalibrationInterceptor.estimateCostForProvider(
                "ollama", 200, 100);
        assertEquals(BigDecimal.ZERO, ollamaCost, "Ollama 本地模型应该零成本");
    }

    @Test
    public void testCloudProviderCostShouldBeCalculated() {
        // 测试2：云端 Provider 应该正常计算成本
        BigDecimal dashscopeCost = costCalibrationInterceptor.estimateCostForProvider(
                "native-dashscope", 200, 100);

        // 预期成本：(200 * 0.004 / 1000) + (100 * 0.012 / 1000) = 0.0008 + 0.0012 = 0.002
        BigDecimal expected = new BigDecimal("0.002000");
        assertEquals(expected, dashscopeCost, "云端 Provider 应该正常计算成本");
    }

    @Test
    public void testNullProviderShouldCalculateNormally() {
        // 测试3：null Provider（旧方法兼容）应该正常计算
        BigDecimal cost = costCalibrationInterceptor.estimateCostForProvider(
                null, 200, 100);

        BigDecimal expected = new BigDecimal("0.002000");
        assertEquals(expected, cost, "null Provider 应该正常计算（向后兼容）");
    }

    @Test
    public void testLargeTokensShouldNotOverflow() {
        // 测试4：大量 token 不应该溢出
        BigDecimal cost = costCalibrationInterceptor.estimateCostForProvider(
                "native-dashscope", 100000, 50000);

        // 预期成本：(100000 * 0.004 / 1000) + (50000 * 0.012 / 1000) = 0.4 + 0.6 = 1.0
        BigDecimal expected = new BigDecimal("1.000000");
        assertEquals(expected, cost, "大量 token 应该正确计算");
    }

    @Test
    public void testOllamaWithLargeTokensStillZero() {
        // 测试5：Ollama 即使有大量 token 也应该零成本
        BigDecimal cost = costCalibrationInterceptor.estimateCostForProvider(
                "ollama", 1000000, 1000000);

        assertEquals(BigDecimal.ZERO, cost, "Ollama 无论 token 数量多少都应该零成本");
    }
}
