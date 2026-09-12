package com.zihan.zhiwei.ai.rag.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.provider.ModelProviderRouter;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatMessage;
import com.zihan.zhiwei.ai.provider.dto.ProviderChatRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhiwei.ai.rag.agentic", name = "enabled", havingValue = "true")
public class DefaultQueryClassifier implements QueryClassifier {

    private static final String SYSTEM_PROMPT = """
            你是企业知识问答路由器。判断回答是否必须检索外部或内部证据。
            只返回 JSON：
            {"needRag":true,"questionType":"FACTUAL|HOW_TO|TROUBLESHOOTING|COMPARISON|SUMMARY|CONVERSATIONAL",
             "needFreshness":false,"multiHop":false,"confidence":0.0,"reason":"原因"}
            普通问候、润色、翻译不需要检索；内部制度、产品文档、操作步骤、事实核对需要检索。
            """;

    private final ModelProviderRouter router;
    private final ObjectMapper objectMapper;
    private final String model;

    public DefaultQueryClassifier(ModelProviderRouter router,
                                  ObjectMapper objectMapper,
                                  @Value("${zhiwei.ai.rag.agentic.classifier-model:qwen-plus}") String model) {
        this.router = router;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public QueryClassification classify(AgenticRagRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty() || isGreeting(query)) {
            return new QueryClassification(false, QuestionType.CONVERSATIONAL,
                    false, false, 1.0, "普通会话无需检索");
        }
        try {
            var response = router.chatWithFailover(new ProviderChatRequest(model, List.of(
                    new ProviderChatMessage("system", SYSTEM_PROMPT),
                    new ProviderChatMessage("user", "用户问题：" + query))));
            JsonNode root = objectMapper.readTree(extractJson(response.content()));
            QuestionType type = parseQuestionType(root.path("questionType").asText());
            return new QueryClassification(
                    root.path("needRag").asBoolean(true),
                    type,
                    root.path("needFreshness").asBoolean(false),
                    root.path("multiHop").asBoolean(false),
                    clamp(root.path("confidence").asDouble(0.5)),
                    root.path("reason").asText("模型分类"));
        } catch (Exception e) {
            log.warn("[AgenticRAG] classifier failed, default to RAG: {}", e.getMessage());
            return new QueryClassification(true, inferType(query), false,
                    looksMultiHop(query), 0.5, "分类失败，保守启用检索");
        }
    }

    private static boolean isGreeting(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？!?,.]", "");
        return List.of("你好", "您好", "hi", "hello", "谢谢", "再见").contains(normalized);
    }

    private static QuestionType inferType(String query) {
        if (query.contains("故障") || query.contains("报错") || query.contains("异常")
                || query.toLowerCase(Locale.ROOT).contains("error")) {
            return QuestionType.TROUBLESHOOTING;
        }
        if (query.contains("如何") || query.contains("怎么") || query.contains("步骤")) {
            return QuestionType.HOW_TO;
        }
        if (query.contains("区别") || query.contains("对比")) {
            return QuestionType.COMPARISON;
        }
        return QuestionType.FACTUAL;
    }

    private static boolean looksMultiHop(String query) {
        return query.contains("和") || query.contains("以及") || query.contains("并且") || query.contains("、");
    }

    private static QuestionType parseQuestionType(String value) {
        try {
            return QuestionType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return QuestionType.FACTUAL;
        }
    }

    private static String extractJson(String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("分类结果不是 JSON");
        }
        return content.substring(start, end + 1);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
