package com.zihan.zhiwei.pojo.dto;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RagEvaluateRequest(
        String dataset,
        @NotEmpty @Size(max = 300) List<@Valid EvalQuery> queries,
        @Min(1) @Max(100) Integer topK,
        @Min(1) @Max(500) Integer candidateK,
        RetrievalStrategy strategy,
        @Valid AbParam abB
) {
    public record EvalQuery(
            String caseId,
            @NotBlank String query,
            String expectedSourceId,
            List<@Valid RelevantDocument> relevantDocuments
    ) {
        public EvalQuery(String query, String expectedSourceId) {
            this(null, query, expectedSourceId, List.of());
        }

        @AssertTrue(message = "expectedSourceId 或 relevantDocuments 至少提供一个")
        public boolean isRelevanceProvided() {
            return (expectedSourceId != null && !expectedSourceId.isBlank())
                    || (relevantDocuments != null && !relevantDocuments.isEmpty());
        }
    }

    public record RelevantDocument(
            @NotBlank String sourceId,
            @Min(1) @Max(3) int relevance
    ) {}

    public record AbParam(
            @NotBlank String variantName,
            @Min(1) @Max(100) int topK,
            @Min(1) @Max(500) int candidateK,
            RetrievalStrategy strategy
    ) {}
}
