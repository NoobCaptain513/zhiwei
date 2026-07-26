package com.zihan.zhiwei.ai.reply;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import com.zihan.zhiwei.ai.intent.AgentIntentAnalyzer;
import com.zihan.zhiwei.ai.intent.ClarifyOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentClarificationService {

    private final AgentIntentAnalyzer intentAnalyzer;

    /**
     * 将意图引导构建为 AgentReply。返回 null 表示无需引导。
     */
    public AgentReply buildClarifyReply(AgentIntent intent) {
        if (intent == null || !intent.needClarification()) {
            return null;
        }

        List<ClarifyOption> suggestions = intent.getSuggestions();
        String prompt = intent.getDisambiguationHint() != null
                && !intent.getDisambiguationHint().isBlank()
                ? intent.getDisambiguationHint()
                : "你想做什么？请选择一个方向：";

        StringBuilder text = new StringBuilder(prompt).append("\n\n");
        List<AgentReply.Card> cards = new ArrayList<>();
        int idx = 1;
        for (ClarifyOption opt : suggestions) {
            text.append(idx).append(". **").append(opt.getLabel()).append("**");
            if (opt.getDescription() != null && !opt.getDescription().isBlank()) {
                text.append(" \u2014 ").append(opt.getDescription());
            }
            text.append("\n");

            Map<String, String> cardFields = new LinkedHashMap<>();
            cardFields.put("intent", opt.getIntent());
            cardFields.put("label", opt.getLabel());
            cardFields.put("question", opt.getQuestion());
            if (opt.getSubOptions() != null && !opt.getSubOptions().isEmpty()) {
                String subJson = opt.getSubOptions().stream()
                        .map(s -> s.getCode() + ":" + s.getLabel())
                        .collect(Collectors.joining(","));
                cardFields.put("subOptions", subJson);
            }
            cards.add(AgentReply.Card.builder()
                    .type("intent_guide")
                    .title(opt.getLabel())
                    .fields(cardFields)
                    .sourceId("clarify-" + opt.getIntent())
                    .build());
            idx++;
        }
        text.append("\n请选择一个方向，或直接描述你的问题。");

        return AgentReply.builder()
                .text(text.toString())
                .cards(cards)
                .intent("clarification")
                .build();
    }

    public AgentIntent handleSelection(String originalMessage, String selectedIntent,
                                        int previousStep, String additionalContext) {
        String effectiveMessage = (additionalContext != null && !additionalContext.isBlank())
                ? additionalContext
                : originalMessage;
        log.info("[Clarify] user selected intent={} step={}", selectedIntent, previousStep);
        return intentAnalyzer.analyzeWithSelection(effectiveMessage, selectedIntent, previousStep);
    }
}
