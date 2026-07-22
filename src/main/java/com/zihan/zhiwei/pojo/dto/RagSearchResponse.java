package com.zihan.zhiwei.pojo.dto;

import java.util.List;

public record RagSearchResponse(
        String query,
        int topK,
        int candidateK,
        List<Item> hits
) {
    public record Item(
            Long id,
            Long documentId,
            String sourceId,
            String title,
            String content,
            double vectorScore,
            double lexicalScore,
            double finalScore
    ) {}
}
