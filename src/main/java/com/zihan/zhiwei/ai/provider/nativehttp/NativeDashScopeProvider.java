package com.zihan.zhiwei.ai.provider.nativehttp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.AbstractNativeHttpProvider;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * D6+D15: Native HTTP DashScope Provider。
 * D15: 实现真正的 SSE 流式输出（stream: true）。
 * D28: 覆写 probe()，用 max_tokens=1 做轻量探测。
 *
 * P1-8 修复：继承 AbstractNativeHttpProvider，删除 ~150 行重复代码。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.native", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NativeDashScopeProvider extends AbstractNativeHttpProvider {

    public static final String PROVIDER_NAME = "native-dashscope";

    private final CostCalibrationInterceptor costCalibrationInterceptor;

    @Value("${zhiwei.ai.native.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${zhiwei.ai.native.api-key:${spring.ai.dashscope.api-key:}}")
    private String apiKey;

    @Value("${zhiwei.ai.native.model:qwen-plus}")
    private String defaultModel;

    @Value("${zhiwei.ai.native.timeout-seconds:60}")
    private long timeoutSeconds;

    public NativeDashScopeProvider(ObjectMapper objectMapper,
                                   CostCalibrationInterceptor costCalibrationInterceptor) {
        super(objectMapper);
        this.costCalibrationInterceptor = costCalibrationInterceptor;
    }

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    // ==================== 子类提供配置值 ====================

    @Override
    protected String getBaseUrl() { return baseUrl; }

    @Override
    protected String getApiKey() { return apiKey; }

    @Override
    protected String getDefaultModel() { return defaultModel; }

    @Override
    protected long getTimeoutSeconds() { return timeoutSeconds; }

    // ==================== 钩子覆写 ====================

    /** DashScope 需要在同步响应后执行成本校准 */
    @Override
    protected void afterSyncResponse(ProviderChatResponse response) {
        costCalibrationInterceptor.calibrate(response);
    }

    /** DashScope 同步错误响应不包含 body（原实现），与 Ollama 不同 */
    @Override
    protected boolean includeBodyInSyncError() {
        return false;
    }
}
