package com.zihan.zhiwei.pojo.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG 质量评估结果。
 */
@Data
@Builder
public class RagEvaluateResponse {

    /** 评估集名称 */
    private String dataset;

    /** 总查询数 */
    private int totalQueries;

    /** 本次评测变体名称与实际检索参数 */
    private String variant;
    private String strategy;
    private int topK;
    private int candidateK;
    private long durationMs;

    /** 命中数（topK 内返回至少一条相关内容） */
    private int hitCount;
    /** 召回率 = hitCount / totalQueries */
    private double recallRate;

    /** 平均排名（命中文档出现在第几位） */
    private double avgRank;

    /** 平均综合分 */
    private double avgFinalScore;

    /** 标准宏平均检索指标。 */
    private Metrics metrics;

    /** 逐条评估明细 */
    private List<QueryDetail> details;

    /** A/B 对比（可选） */
    private AbComparison abComparison;

    @Data
    @Builder
    public static class QueryDetail {
        private String caseId;
        private String query;
        private boolean hit;
        private int topRank;
        private double finalScore;
        private String matchedSource;
        /** 人工标注的期望 sourceId */
        private String expectedSource;
        private List<RelevanceJudgment> relevantDocuments;
        private List<String> retrievedSourceIds;
        private Metrics metrics;
    }

    @Data
    @Builder
    public static class RelevanceJudgment {
        private String sourceId;
        private int relevance;
    }

    @Data
    @Builder
    public static class Metrics {
        private int k;
        private double hitRate;
        private double recall;
        private double precision;
        private double mrr;
        private double ndcg;
    }

    @Data
    @Builder
    public static class AbComparison {
        private String variantA;
        private String variantB;
        private String strategyA;
        private String strategyB;
        private double recallA;
        private double recallB;
        private double avgRankA;
        private double avgRankB;
        private Metrics metricsA;
        private Metrics metricsB;
        private String winner;
    }
}