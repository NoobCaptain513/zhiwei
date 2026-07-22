package com.zihan.zhiwei.ai.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * D12: 意图识别器（规则 + 关键词，轻量高效）。
 * 5 类意图：fault / log / deploy / ticket / rag
 * 返回按置信度降序排列的 Score 列表。
 */
@Slf4j
@Component
public class AgentIntentAnalyzer {

    /** 关键词 → 意图映射（可按需扩展 / 配置化） */
    private static final Map<String, List<String>> KEYWORDS = Map.of(
            AgentIntent.FAULT,  List.of("故障", "异常", "报错", "宕机", "挂了", "down", "error", "exception", "告警", "alert", "超时", "timeout", "500", "502", "503", "OOM", "死锁"),
            AgentIntent.LOG,    List.of("日志", "log", "查看日志", "查日志", "logcat", "tail", "搜索日志", "错误日志", "access log"),
            AgentIntent.DEPLOY, List.of("部署", "发布", "上线", "回滚", "rollback", "deploy", "CI/CD", "pipeline", "构建", "build"),
            AgentIntent.TICKET, List.of("工单", "提单", "ticket", "issue", "问题单", "提一个", "创建工单", "分配"),
            AgentIntent.RAG,    List.of("知识", "文档", "wiki", "怎么", "是什么", "原理", "介绍", "说明", "帮助", "教程", "如何")
    );

    /**
     * 识别意图，返回按置信度降序的列表。
     */
    public AgentIntent analyze(String message) {
        if (message == null || message.isBlank()) {
            return AgentIntent.builder()
                    .primary(AgentIntent.RAG)
                    .ranked(List.of(new AgentIntent.Score(AgentIntent.RAG, 1.0)))
                    .build();
        }

        String lower = message.toLowerCase(Locale.ROOT);
        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : KEYWORDS.entrySet()) {
            double score = 0;
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    score += 1.0;
                    // 完整词匹配加分
                    if (Pattern.compile("\\b" + Pattern.quote(kw.toLowerCase(Locale.ROOT)) + "\\b").matcher(lower).find()) {
                        score += 0.3;
                    }
                }
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }

        // 无任何命中 → 默认 RAG（知识检索兜底）
        if (scores.isEmpty()) {
            scores.put(AgentIntent.RAG, 0.5);
        }

        // 归一化到 [0, 1]
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        List<AgentIntent.Score> ranked = scores.entrySet().stream()
                .map(e -> new AgentIntent.Score(e.getKey(), Math.min(1.0, e.getValue() / max)))
                .sorted(Comparator.comparingDouble(AgentIntent.Score::getConfidence).reversed())
                .collect(Collectors.toList());

        String primary = ranked.get(0).getIntent();
        log.debug("[Intent] message='{}' -> primary={} scores={}", message, primary, ranked);
        return AgentIntent.builder().primary(primary).ranked(ranked).build();
    }
}