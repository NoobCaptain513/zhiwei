package com.zihan.zhiwei.pojo.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record RagEvaluateRequest(
        String dataset,
        List<EvalQuery> queries,
        Integer topK,
        Integer candidateK,
        AbParam abB
) {
    public record EvalQuery(
            @NotBlank String query,
            String expectedSourceId
    ) {}

    public record AbParam(
            String variantName,
            int topK,
            int candidateK
    ) {}
}
