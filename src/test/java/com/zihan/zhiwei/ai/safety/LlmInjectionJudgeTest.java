package com.zihan.zhiwei.ai.safety;

import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LLM 注入裁判安全默认值")
class LlmInjectionJudgeTest {

    @Test
    @DisplayName("类内缺省配置同样采用 fail-closed")
    void annotationDefaultsToFailClosed() throws Exception {
        Value value = LlmInjectionJudge.class.getDeclaredField("failOpen").getAnnotation(Value.class);

        assertThat(value.value()).isEqualTo("${zhiwei.ai.safety.judge.fail-open:false}");
    }

    @Test
    @DisplayName("只接受严格 CLEAN，其他模型输出按注入阻断")
    void rejectsAmbiguousVerdict() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        LlmInjectionJudge judge = judge(router);
        when(router.chatWithFailover(any())).thenReturn(
                new ProviderChatResponse("probably clean", "m", "p", 0, 0, 0));

        assertThat(judge.judge("灰区输入")).isEqualTo(LlmInjectionJudge.Verdict.INJECTION);
    }

    @Test
    @DisplayName("严格 CLEAN 输出正常放行")
    void acceptsExactCleanVerdict() {
        ModelProviderRouter router = mock(ModelProviderRouter.class);
        LlmInjectionJudge judge = judge(router);
        when(router.chatWithFailover(any())).thenReturn(
                new ProviderChatResponse(" CLEAN ", "m", "p", 0, 0, 0));

        assertThat(judge.judge("正常输入")).isEqualTo(LlmInjectionJudge.Verdict.CLEAN);
    }

    private static LlmInjectionJudge judge(ModelProviderRouter router) {
        LlmInjectionJudge judge = new LlmInjectionJudge(router);
        ReflectionTestUtils.setField(judge, "judgeModel", "judge");
        ReflectionTestUtils.setField(judge, "maxChars", 1500);
        ReflectionTestUtils.setField(judge, "failOpen", false);
        return judge;
    }
}
