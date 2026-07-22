package com.zihan.zhiwei.ai.prompt;

import com.zihan.zhiwei.ai.intent.AgentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * D12: Prompt 模板化 + 动态变量注入。
 * 根据意图选择 system prompt，再填入变量（当前时间、用户名、RAG 上下文等）。
 */
@Service
public class AiPromptService {

    @Value("${zhiwei.ai.prompt.app-name:智维}")
    private String appName;

    /** 5 类意图对应的 system prompt 模板 */
    private static final Map<String, String> INTENT_PROMPTS = Map.of(
            AgentIntent.FAULT, """
                你是 %s 平台的故障排查助手。
                用户可能遇到了服务异常、报错、宕机等问题。
                请按以下步骤回答：
                1. 先描述可能的原因
                2. 给出排查命令或操作步骤
                3. 如有必要，建议创建工单
                回答要专业、简洁，优先用中文。
                """,
            AgentIntent.LOG, """
                你是 %s 平台的日志查询助手。
                用户需要查看或搜索系统日志。
                请按以下步骤回答：
                1. 给出具体的日志查询命令（如 grep、tail、kubectl logs 等）
                2. 说明日志文件路径和常见关键字
                3. 如日志量大，建议加过滤条件
                回答要专业、简洁，优先用中文。
                """,
            AgentIntent.DEPLOY, """
                你是 %s 平台的部署发布助手。
                用户可能需要部署、发布、回滚等操作指导。
                请按以下步骤回答：
                1. 确认部署目标（环境、服务名）
                2. 给出部署/回滚命令或流程
                3. 提醒注意事项（灰度、回滚方案等）
                回答要专业、简洁，优先用中文。
                """,
            AgentIntent.TICKET, """
                你是 %s 平台的工单助手。
                用户需要创建、查询或管理工单。
                请按以下步骤回答：
                1. 询问或确认工单标题和描述
                2. 给出建议的优先级和分类
                3. 提供创建工单的具体操作
                回答要专业、简洁，优先用中文。
                """,
            AgentIntent.RAG, """
                你是 %s 平台的知识问答助手。
                请基于知识库检索结果回答用户问题。
                如果知识库没有相关内容，请如实说明并给出通用建议。
                回答要专业、简洁，优先用中文。
                """
    );

    /** 根据意图生成 system prompt */
    public String buildSystemPrompt(String intent) {
        String template = INTENT_PROMPTS.getOrDefault(intent, INTENT_PROMPTS.get(AgentIntent.RAG));
        return template.formatted(appName);
    }

    /** 带变量注入的 system prompt */
    public String buildSystemPrompt(String intent, Map<String, String> variables) {
        String prompt = buildSystemPrompt(intent);
        if (variables != null) {
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                prompt = prompt.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return prompt;
    }

    /** 默认 system prompt（非 Agent 模式） */
    public String defaultSystemPrompt() {
        return "你是 %s 智能助手，帮助用户解答各种问题。回答简洁、专业，优先用中文。".formatted(appName);
    }
}