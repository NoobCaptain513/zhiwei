package com.zihan.zhiwei.ai.rag.dto;

import java.util.List;

/**
 * D31: 查询改写结果。
 */
public record QueryRewriteResult(
        String original,
        String rewritten,
        List<String> subQuestions
) {
    public boolean needMultiQuery() {
        return subQuestions != null && !subQuestions.isEmpty();
    }

    public List<String> allQueries() {
        if (needMultiQuery()) {
            return subQuestions;
        }
        return rewritten != null && !rewritten.isBlank()
                ? List.of(rewritten)
                : List.of(original);
    }
}
