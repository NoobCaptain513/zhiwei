package com.zihan.zhiwei.pojo.dto;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 评测请求校验")
class RagEvaluateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("多文档分级标注应通过级联校验")
    void shouldAcceptValidGradedJudgments() {
        RagEvaluateRequest request = new RagEvaluateRequest(
                "ops-v1",
                List.of(new RagEvaluateRequest.EvalQuery(
                        "case-001", "如何处理 Redis 雪崩", null,
                        List.of(
                                new RagEvaluateRequest.RelevantDocument("redis-runbook", 3),
                                new RagEvaluateRequest.RelevantDocument("cache-guide", 2)))),
                5, 20, RetrievalStrategy.HYBRID, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("评测集必须包含 1 到 300 条查询")
    void shouldRejectEmptyEvaluationSet() {
        RagEvaluateRequest request = new RagEvaluateRequest(
                "ops-v1", List.of(), 5, 20, RetrievalStrategy.HYBRID, null);

        assertThat(validator.validate(request)).isNotEmpty();
    }

    @Test
    @DisplayName("嵌套查询、sourceId 和相关度等级必须校验")
    void shouldCascadeNestedValidation() {
        RagEvaluateRequest request = new RagEvaluateRequest(
                "ops-v1",
                List.of(new RagEvaluateRequest.EvalQuery(
                        "case-001", " ", null,
                        List.of(new RagEvaluateRequest.RelevantDocument(" ", 4)))),
                0, 501, RetrievalStrategy.HYBRID, null);

        assertThat(validator.validate(request)).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("兼容旧 expectedSourceId 且无任何相关文档时拒绝")
    void shouldRequireLegacyOrGradedJudgment() {
        RagEvaluateRequest validLegacy = new RagEvaluateRequest(
                "legacy", List.of(new RagEvaluateRequest.EvalQuery("query", "doc-a")),
                5, 20, null, null);
        RagEvaluateRequest invalid = new RagEvaluateRequest(
                "invalid",
                List.of(new RagEvaluateRequest.EvalQuery("case", "query", null, List.of())),
                5, 20, null, null);

        assertThat(validator.validate(validLegacy)).isEmpty();
        assertThat(validator.validate(invalid)).isNotEmpty();
    }
}
