package com.zihan.zhiwei.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 基线数据集完整性")
class RagEvaluationDatasetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("静态标注集应包含 100 到 300 条唯一且可解析的查询")
    void shouldContainValidEvaluationCases() throws IOException {
        JsonNode corpus = objectMapper.readTree(Path.of("evaluation/rag/corpus-v1.json").toFile());
        JsonNode evalSet = objectMapper.readTree(Path.of("evaluation/rag/eval-set-v1.json").toFile());
        Set<String> corpusSources = new HashSet<>();
        corpus.path("documents").forEach(document ->
                corpusSources.add(document.path("sourceId").asText()));

        JsonNode queries = evalSet.path("queries");
        assertThat(queries.size()).isBetween(100, 300);
        Set<String> caseIds = new HashSet<>();
        int multiDocumentCases = 0;
        for (JsonNode query : queries) {
            assertThat(query.path("query").asText()).isNotBlank();
            assertThat(caseIds.add(query.path("caseId").asText())).isTrue();
            JsonNode judgments = query.path("relevantDocuments");
            assertThat(judgments.isArray()).isTrue();
            assertThat(judgments.size()).isPositive();
            if (judgments.size() > 1) {
                multiDocumentCases++;
            }
            Set<String> judgedSources = new HashSet<>();
            for (JsonNode judgment : judgments) {
                String sourceId = judgment.path("sourceId").asText();
                assertThat(corpusSources).contains(sourceId);
                assertThat(judgedSources.add(sourceId)).isTrue();
                assertThat(judgment.path("relevance").asInt()).isBetween(1, 3);
            }
        }
        assertThat(multiDocumentCases).isGreaterThanOrEqualTo(20);
    }
}
