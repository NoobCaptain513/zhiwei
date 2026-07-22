package com.zihan.zhiwei.ai.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentIntentAnalyzer 意图识别测试")
class AgentIntentAnalyzerTest {

    private final AgentIntentAnalyzer analyzer = new AgentIntentAnalyzer();

    @Nested
    @DisplayName("单意图匹配")
    class SingleIntentTests {

        @Test
        @DisplayName("Redis 宕机 → primary=fault")
        void shouldDetectFault() {
            AgentIntent result = analyzer.analyze("Redis 宕机报错，无法启动");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.FAULT);
        }

        @Test
        @DisplayName("查看错误日志 → primary=log")
        void shouldDetectLog() {
            AgentIntent result = analyzer.analyze("查看 nginx 错误日志");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.LOG);
        }

        @Test
        @DisplayName("部署新版本 → primary=deploy")
        void shouldDetectDeploy() {
            AgentIntent result = analyzer.analyze("部署 v2.0 到生产环境");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.DEPLOY);
        }

        @Test
        @DisplayName("创建工单 → primary=ticket")
        void shouldDetectTicket() {
            AgentIntent result = analyzer.analyze("帮我创建一个磁盘问题的工单");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.TICKET);
        }

        @Test
        @DisplayName("知识询问 → primary=rag")
        void shouldDetectRag() {
            AgentIntent result = analyzer.analyze("Redis 集群的原理是什么");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.RAG);
        }
    }

    @Nested
    @DisplayName("多意图混合")
    class MultiIntentTests {

        @Test
        @DisplayName("故障 + 日志 → 两个意图都有分，包含 fault 和 log")
        void shouldDetectMultiple() {
            AgentIntent result = analyzer.analyze("nginx 报错了，帮我查一下日志");

            List<String> intents = result.getRanked().stream()
                    .map(AgentIntent.Score::getIntent).toList();
            assertThat(intents).contains(AgentIntent.FAULT, AgentIntent.LOG);
            assertThat(result.getRanked()).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("降序排列，第一个置信度最高")
        void shouldBeSortedDescending() {
            AgentIntent result = analyzer.analyze("部署后报错了，需要查日志");

            List<AgentIntent.Score> ranked = result.getRanked();
            for (int i = 1; i < ranked.size(); i++) {
                assertThat(ranked.get(i - 1).getConfidence())
                        .isGreaterThanOrEqualTo(ranked.get(i).getConfidence());
            }
        }
    }

    @Nested
    @DisplayName("英文关键词")
    class EnglishKeywordTests {

        @Test
        @DisplayName("error → primary=fault")
        void shouldDetectError() {
            AgentIntent result = analyzer.analyze("Service error occurred");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.FAULT);
        }

        @Test
        @DisplayName("deploy → primary=deploy")
        void shouldDetectDeployEnglish() {
            AgentIntent result = analyzer.analyze("Please deploy the new build");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.DEPLOY);
        }

        @Test
        @DisplayName("ticket → primary=ticket")
        void shouldDetectTicketEnglish() {
            AgentIntent result = analyzer.analyze("Create a ticket for disk issue");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.TICKET);
        }

        @Test
        @DisplayName("rollback → primary=deploy")
        void shouldDetectRollback() {
            AgentIntent result = analyzer.analyze("紧急回滚 v2.3.1");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.DEPLOY);
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("空消息 → 默认 RAG")
        void shouldDefaultToRagForEmpty() {
            AgentIntent result = analyzer.analyze("");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.RAG);
            assertThat(result.getRanked().get(0).getConfidence()).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("null 消息 → 默认 RAG")
        void shouldDefaultToRagForNull() {
            AgentIntent result = analyzer.analyze(null);

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.RAG);
        }

        @Test
        @DisplayName("无任何关键词 → 默认 RAG, 0.5 置信度")
        void shouldDefaultToRagForUnknown() {
            AgentIntent result = analyzer.analyze("今天天气真好");

            assertThat(result.getPrimary()).isEqualTo(AgentIntent.RAG);
            assertThat(result.getRanked()).hasSize(1);
            assertThat(result.getRanked().get(0).getConfidence()).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("大小写不敏感")
        void shouldBeCaseInsensitive() {
            AgentIntent result = analyzer.analyze("ERROR occurred in DEPLOY pipeline");

            List<String> intents = result.getRanked().stream()
                    .map(AgentIntent.Score::getIntent).toList();
            assertThat(intents).contains(AgentIntent.FAULT, AgentIntent.DEPLOY);
            assertThat(result.getRanked()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("常量验证")
    class ConstantsTests {

        @Test
        @DisplayName("5 个意图名正确")
        void shouldHaveCorrectIntentNames() {
            assertThat(AgentIntent.FAULT).isEqualTo("fault");
            assertThat(AgentIntent.LOG).isEqualTo("log");
            assertThat(AgentIntent.DEPLOY).isEqualTo("deploy");
            assertThat(AgentIntent.TICKET).isEqualTo("ticket");
            assertThat(AgentIntent.RAG).isEqualTo("rag");
        }

        @Test
        @DisplayName("ALL_INTENTS 包含 5 个意图")
        void shouldHaveFiveIntents() {
            assertThat(AgentIntent.ALL_INTENTS).containsExactly("fault", "log", "deploy", "ticket", "rag");
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
