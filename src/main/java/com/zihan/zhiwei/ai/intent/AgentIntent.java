package com.zihan.zhiwei.ai.intent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D12: Agent 意图识别结果。
 * D29: 新增 lowConfidence + suggestions，置信度不足时主动引导。
 */
@Data
@Builder
public class AgentIntent {

    private String primary;
    private List<Score> ranked;

    @Builder.Default
    private boolean lowConfidence = false;

    @Builder.Default
    private List<ClarifyOption> suggestions = List.of();

    @Builder.Default
    private String disambiguationHint = "";

    @Builder.Default
    private int clarificationStep = 1;

    public boolean needClarification() {
        return lowConfidence && !suggestions.isEmpty();
    }

    public static final String FAULT   = "fault";
    public static final String LOG     = "log";
    public static final String DEPLOY  = "deploy";
    public static final String TICKET  = "ticket";
    public static final String RAG     = "rag";

    public static final List<String> ALL_INTENTS =
            List.of(FAULT, LOG, DEPLOY, TICKET, RAG);

    @Data
    public static class Score {
        private String intent;
        private double confidence;

        public Score(String intent, double confidence) {
            this.intent = intent;
            this.confidence = confidence;
        }
    }
}
