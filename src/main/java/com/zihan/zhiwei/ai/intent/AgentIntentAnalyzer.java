package com.zihan.zhiwei.ai.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * D12 增强：意图识别器 + 模糊引导。
 * D29: 三段式置信度判断 + 二选一话术 + 多轮收敛。
 */
@Slf4j
@Component
public class AgentIntentAnalyzer {

    private final IntentTree intentTree;

    @Value("${zhiwei.ai.intent.confidence-high:0.65}")
    private double confidenceHigh;

    @Value("${zhiwei.ai.intent.confidence-low:0.20}")
    private double confidenceLow;

    @Value("${zhiwei.ai.intent.ambiguity-margin:0.20}")
    private double ambiguityMargin;

    @Value("${zhiwei.ai.intent.max-clarify-steps:2}")
    private int maxClarifySteps;

    public AgentIntentAnalyzer(IntentTree intentTree) {
        this.intentTree = intentTree;
    }

    private static final Map<String, List<String>> KEYWORDS = Map.of(
            AgentIntent.FAULT,  List.of("故障", "异常", "报错", "宕机", "挂了", "down", "error", "exception", "告警", "alert", "超时", "timeout", "500", "502", "503", "OOM", "死锁"),
            AgentIntent.LOG,    List.of("日志", "log", "查看日志", "查日志", "logcat", "tail", "搜索日志", "错误日志", "access log"),
            AgentIntent.DEPLOY, List.of("部署", "发布", "上线", "回滚", "rollback", "deploy", "CI/CD", "pipeline", "构建", "build"),
            AgentIntent.TICKET, List.of("工单", "提单", "ticket", "issue", "问题单", "提一个", "创建工单", "分配"),
            AgentIntent.RAG,    List.of("知识", "文档", "wiki", "怎么", "是什么", "原理", "介绍", "说明", "帮助", "教程", "如何")
    );

    /**
     * 分析意图。
     */
    public AgentIntent analyze(String message) {
        return analyze(message, 1);
    }

    private AgentIntent analyze(String message, int clarifyStep) {
        if (message == null || message.isBlank()) {
            return confidentResult(AgentIntent.RAG);
        }

        List<AgentIntent.Score> ranked = scoreKeywords(message);

        // 完全没命中关键词 → 列出全部意图
        if (ranked.size() == 1 && ranked.get(0).getConfidence() == 0.0) {
            List<AgentIntent.Score> allIntents = AgentIntent.ALL_INTENTS.stream()
                    .map(i -> new AgentIntent.Score(i, 0.0))
                    .toList();
            return buildClarification(allIntents, clarifyStep);
        }

        AgentIntent.Score top = ranked.get(0);

        if (top.getConfidence() >= confidenceHigh) {
            return confidentResult(top.getIntent(), ranked);
        }

        if (top.getConfidence() < confidenceLow) {
            return buildClarification(ranked, clarifyStep);
        }

        boolean ambiguous = ranked.size() >= 2
                && Math.abs(ranked.get(0).getConfidence() - ranked.get(1).getConfidence()) <= ambiguityMargin;

        if (ambiguous) {
            return buildClarification(ranked, clarifyStep);
        }

        return confidentResult(top.getIntent(), ranked);
    }

    /**
     * 用户选择意图后的二次分析。
     */
    public AgentIntent analyzeWithSelection(String message, String selectedIntent, int previousStep) {
        if (previousStep >= maxClarifySteps) {
            log.info("[Intent] max steps reached, accepting selection={}", selectedIntent);
            return confidentResult(selectedIntent);
        }

        AgentIntent reAnalysis = analyze(message, previousStep + 1);

        List<AgentIntent.Score> reranked = new ArrayList<>();
        reranked.add(new AgentIntent.Score(selectedIntent, 0.9));
        for (AgentIntent.Score s : reAnalysis.getRanked()) {
            if (!s.getIntent().equals(selectedIntent)) {
                reranked.add(s);
            }
        }

        return AgentIntent.builder()
                .primary(selectedIntent)
                .ranked(reranked)
                .lowConfidence(false)
                .clarificationStep(previousStep + 1)
                .build();
    }

    // ==================== 内部方法 ====================

    private List<AgentIntent.Score> scoreKeywords(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        Map<String, Double> scores = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : KEYWORDS.entrySet()) {
            double score = 0;
            for (String kw : entry.getValue()) {
                if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                    score += 1.0;
                    if (Pattern.compile("\\b" + Pattern.quote(kw.toLowerCase(Locale.ROOT)) + "\\b")
                            .matcher(lower).find()) {
                        score += 0.3;
                    }
                }
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }

        if (scores.isEmpty()) {
            return List.of(new AgentIntent.Score(AgentIntent.RAG, 0.0));
        }

        double max = scores.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(1.0);
        return scores.entrySet().stream()
                .map(e -> new AgentIntent.Score(e.getKey(),
                        Math.min(1.0, e.getValue() / max)))
                .sorted(Comparator.comparingDouble(AgentIntent.Score::getConfidence).reversed())
                .collect(Collectors.toList());
    }

    private AgentIntent confidentResult(String primary) {
        return AgentIntent.builder()
                .primary(primary)
                .ranked(List.of(new AgentIntent.Score(primary, 1.0)))
                .build();
    }

    private AgentIntent confidentResult(String primary, List<AgentIntent.Score> ranked) {
        return AgentIntent.builder()
                .primary(primary)
                .ranked(ranked)
                .build();
    }

    private AgentIntent buildClarification(List<AgentIntent.Score> ranked, int step) {
        int count = Math.min(3, ranked.size());
        List<AgentIntent.Score> top = ranked.subList(0, count);
        List<ClarifyOption> suggestions = intentTree.buildClarifyOptions(top);

        String hint = (count == 2) ? intentTree.buildDisambiguationHint(top) : "";

        log.info("[Intent] clarification step={} top={} hint='{}'",
                step, top.stream().map(s -> s.getIntent() + "=" + s.getConfidence())
                        .collect(Collectors.joining(", ")), hint);

        return AgentIntent.builder()
                .primary(ranked.get(0).getIntent())
                .ranked(ranked)
                .lowConfidence(true)
                .suggestions(suggestions)
                .disambiguationHint(hint)
                .clarificationStep(step)
                .build();
    }
}
