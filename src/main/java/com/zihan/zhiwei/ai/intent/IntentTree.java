package com.zihan.zhiwei.ai.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class IntentTree {

    private record IntentNode(
            String label,
            String description,
            String question,
            List<ClarifyOption.SubOption> subOptions
    ) {}

    private static final Map<String, IntentNode> NODES = Map.of(
            AgentIntent.FAULT, new IntentNode(
                    "故障排查", "排查服务异常、报错、宕机等问题",
                    "请问是哪种类型的故障？",
                    List.of(
                            new ClarifyOption.SubOption("service", "服务故障 (报错/异常/宕机)"),
                            new ClarifyOption.SubOption("infra", "基础设施故障 (CPU/内存/网络)")
                    )
            ),
            AgentIntent.LOG, new IntentNode(
                    "日志查询", "查看、搜索、分析应用日志",
                    "需要查询哪类日志？",
                    List.of(
                            new ClarifyOption.SubOption("app", "应用日志"),
                            new ClarifyOption.SubOption("error", "错误日志"),
                            new ClarifyOption.SubOption("access", "访问日志")
                    )
            ),
            AgentIntent.DEPLOY, new IntentNode(
                    "部署发布", "部署、发布、回滚操作",
                    "是哪种部署操作？",
                    List.of(
                            new ClarifyOption.SubOption("release", "发布上线"),
                            new ClarifyOption.SubOption("rollback", "回滚")
                    )
            ),
            AgentIntent.TICKET, new IntentNode(
                    "工单管理", "创建、查询、分配工单",
                    "需要什么工单操作？",
                    List.of(
                            new ClarifyOption.SubOption("create", "创建工单"),
                            new ClarifyOption.SubOption("query", "查询工单状态")
                    )
            ),
            AgentIntent.RAG, new IntentNode(
                    "知识检索", "查询知识库、文档、Wiki",
                    "想了解哪方面的知识？",
                    List.of()
            )
    );

    public List<ClarifyOption> buildClarifyOptions(List<AgentIntent.Score> topIntents) {
        List<ClarifyOption> options = new ArrayList<>();
        for (AgentIntent.Score scored : topIntents) {
            IntentNode node = NODES.get(scored.getIntent());
            if (node == null) {
                continue;
            }
            options.add(ClarifyOption.builder()
                    .intent(scored.getIntent())
                    .label(node.label())
                    .question(node.question())
                    .description(node.description())
                    .subOptions(node.subOptions())
                    .build());
        }
        return options;
    }

    public String buildDisambiguationHint(List<AgentIntent.Score> topTwo) {
        if (topTwo.size() < 2) {
            return null;
        }
        IntentNode a = NODES.get(topTwo.get(0).getIntent());
        IntentNode b = NODES.get(topTwo.get(1).getIntent());
        if (a == null || b == null) {
            return null;
        }
        return "你是在\u300c" + a.label() + "\u300d还是\u300c" + b.label() + "\u300d？";
    }

    public String labelOf(String intent) {
        IntentNode node = NODES.get(intent);
        return node != null ? node.label() : intent;
    }

    public String descriptionOf(String intent) {
        IntentNode node = NODES.get(intent);
        return node != null ? node.description() : "";
    }
}
