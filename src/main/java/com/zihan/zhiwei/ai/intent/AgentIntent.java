package com.zihan.zhiwei.ai.intent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * D12: Agent 意图识别结果。
 * 按置信度降序排列，前端/路由器取第一个即可。
 */
@Data
@Builder
public class AgentIntent {

    /** 主意图 */
    private String primary;
    /** 各意图的置信度，降序 */
    private List<Score> ranked;

    /** 意图枚举常量 */
    public static final String FAULT   = "fault";    // 故障排查
    public static final String LOG     = "log";      // 日志查询
    public static final String DEPLOY  = "deploy";   // 部署相关
    public static final String TICKET  = "ticket";   // 工单
    public static final String RAG     = "rag";       // 知识检索

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