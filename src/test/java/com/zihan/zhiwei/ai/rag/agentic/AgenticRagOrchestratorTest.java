package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagCheckpointSnapshot;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;
import com.zihan.zhiwei.ai.rag.agentic.model.NextAction;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;
import com.zihan.zhiwei.ai.rag.agentic.model.QuestionType;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;
import com.zihan.zhiwei.ai.agent.runtime.AgentNodeObserver;
import com.zihan.zhiwei.ai.agent.runtime.AgentRunContext;
import com.zihan.zhiwei.ai.agent.runtime.TokenBudget;
import com.zihan.zhiwei.ai.memory.CheckpointService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.mapper.ConversationMapper;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticRagOrchestratorTest {

    @Mock private QueryPlanner planner;
    @Mock private Retriever retriever;
    @Mock private EvidenceGrader evidenceGrader;
    @Mock private FeedbackQueryRewriter queryRewriter;
    @Mock private AnswerGenerator answerGenerator;

    @Test
    void shouldStartPlanningWithoutClassifyingWhetherRagIsRequiredAgain() {
        AgenticRagRequest request = new AgenticRagRequest(
                "Redis MOVED 怎么处理", null, null, "qwen-plus");
        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(plan("Redis MOVED", 20));
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(RetrievalResult.empty());
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any())).thenReturn(new EvidenceGrade(
                false, List.of(), List.of(), List.of("缺少处置步骤"), List.of(),
                NextAction.ABSTAIN, "无法可靠检索"));
        AgenticRagResult expected = new AgenticRagResult(true, "证据不足", List.of(),
                1, false, false, "INSUFFICIENT_EVIDENCE", "system", "none",
                0, 0, 0, false, 1);
        when(answerGenerator.abstain(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        assertThat(orchestrator.execute(request)).isSameAs(expected);
        verify(planner).plan(org.mockito.ArgumentMatchers.any());
        verify(retriever).retrieve(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRewriteAndRetrieveAgainWhenEvidenceIsInsufficient() {
        AgenticRagRequest request = new AgenticRagRequest("Redis MOVED 怎么处理", null, null, "qwen-plus");
        RetrievalPlan initialPlan = plan("MOVED 原因", 20);
        RetrievalPlan rewrittenPlan = plan("Redis 7 MOVED 标准处置步骤", 40);
        EvidenceGrade insufficient = new EvidenceGrade(false, List.of(), List.of(),
                List.of("缺少处置步骤"), List.of(), NextAction.EXPAND_RECALL, "证据不足");
        EvidenceGrade sufficient = new EvidenceGrade(true, List.of(7L), List.of("处置步骤"),
                List.of(), List.of(), NextAction.ANSWER, "证据充分");
        AgenticRagResult expected = new AgenticRagResult(true, "按手册处理 [E7]", List.of(),
                2, true, false, "ANSWERED", "test", "qwen-plus", 1, 1, 2, false, 10);

        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(initialPlan);
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(RetrievalResult.empty(), RetrievalResult.empty());
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any()))
                .thenReturn(insufficient, sufficient);
        when(queryRewriter.rewrite(org.mockito.ArgumentMatchers.any())).thenReturn(rewrittenPlan);
        when(answerGenerator.generateAndVerify(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        AgenticRagResult result = orchestrator.execute(request);

        assertThat(result).isSameAs(expected);
        verify(retriever, org.mockito.Mockito.times(2)).retrieve(org.mockito.ArgumentMatchers.any());
        verify(queryRewriter).rewrite(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAbstainImmediatelyWhenGraderRequestsAbstention() {
        AgenticRagRequest request = new AgenticRagRequest("敏感事实", null, null, "qwen-plus");
        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(plan("事实", 20));
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(RetrievalResult.empty());
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any())).thenReturn(new EvidenceGrade(
                false, List.of(), List.of(), List.of("来源不可用"), List.of(),
                NextAction.ABSTAIN, "无法可靠检索"));
        AgenticRagResult expected = new AgenticRagResult(true, "证据不足", List.of(),
                1, false, false, "INSUFFICIENT_EVIDENCE", "system", "none",
                0, 0, 0, false, 1);
        when(answerGenerator.abstain(org.mockito.ArgumentMatchers.any())).thenReturn(expected);
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);

        assertThat(orchestrator.execute(request)).isSameAs(expected);
        verify(queryRewriter, never()).rewrite(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldTerminateDeterministicallyWhenTokenBudgetIsExhausted() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(10, 2, 5, Duration.ofSeconds(5)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        AgenticRagRequest request = new AgenticRagRequest(
                "需要检索的问题", null, null, "qwen-plus", context);
        QueryPlanner budgetExhaustingPlanner = state -> {
            state.getRequest().runContext().reserve("plan", 20, 10, false);
            throw new AssertionError("unreachable");
        };
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                budgetExhaustingPlanner, retriever, evidenceGrader,
                queryRewriter, answerGenerator, 2);

        AgenticRagResult result = orchestrator.execute(request);

        assertThat(result.terminationReason()).isEqualTo("TOKEN_BUDGET_EXHAUSTED");
        assertThat(result.answer()).contains("预算");
        verify(planner, never()).plan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldPersistCompleteResumableStateAndNextNode() {
        AgenticRagRequest request = new AgenticRagRequest(
                "Redis MOVED 怎么处理", "history", null, "qwen-plus", "alice", 10L);
        RetrievalPlan retrievalPlan = plan("Redis MOVED", 20);
        RagHit hit = new RagHit(new KnowledgeChunk(7L, 3L, "runbook", "Redis 手册",
                "执行 CLUSTER NODES 检查槽位。", 12, LocalDateTime.now()), 0.9, 0.8, 0.88);
        RetrievalResult retrieval = new RetrievalResult(List.of(hit), 12L);
        EvidenceGrade sufficient = new EvidenceGrade(true, List.of(7L), List.of("处置步骤"),
                List.of(), List.of(), NextAction.ANSWER, "证据充分");
        AgenticRagResult expected = new AgenticRagResult(true, "按手册处理 [E7]", List.of(),
                1, false, false, "ANSWERED", "test", "qwen-plus", 1, 1, 2, false, 10);
        when(planner.plan(org.mockito.ArgumentMatchers.any())).thenReturn(retrievalPlan);
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any())).thenReturn(retrieval);
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any())).thenReturn(sufficient);
        when(answerGenerator.generateAndVerify(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        CheckpointService checkpoints = mock(CheckpointService.class);
        MemoryProperties memory = new MemoryProperties();
        memory.setEnabled(true);
        java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(1L);
        when(checkpoints.create(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            CheckpointService.CreateCommand command = invocation.getArgument(0);
            return new AgentCheckpoint(9L, command.runId(), command.conversationId(),
                    command.userId(), command.checkpointType(), command.nodeName(),
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(command.state()), command.status(),
                    command.sequenceNo(), 1L, null, null, LocalDateTime.now().plusDays(1),
                    LocalDateTime.now(), LocalDateTime.now());
        });
        when(checkpoints.updateProgress(anyString(), eq(9L), anyLong(), anyString(), any(), anyInt(),
                anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            long nextVersion = version.incrementAndGet();
            return new AgentCheckpoint(9L, "run", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG,
                    invocation.getArgument(3),
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                            .valueToTree(invocation.getArgument(4)),
                    AgentCheckpoint.Status.RUNNING, invocation.getArgument(5), nextVersion,
                    null, null, LocalDateTime.now().plusDays(1), LocalDateTime.now(), LocalDateTime.now());
        });
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);
        ReflectionTestUtils.setField(orchestrator, "checkpointService", checkpoints);
        ReflectionTestUtils.setField(orchestrator, "memoryProperties", memory);

        assertThat(orchestrator.execute(request)).isSameAs(expected);

        org.mockito.ArgumentCaptor<CheckpointState> states =
                org.mockito.ArgumentCaptor.forClass(CheckpointState.class);
        verify(checkpoints, org.mockito.Mockito.times(1)).create(any());
        verify(checkpoints, org.mockito.Mockito.atLeastOnce()).updateProgress(
                eq("alice"), eq(9L), anyLong(), eq("generate"), states.capture(), anyInt(),
                eq("agentic-rag"), eq("node advanced"), anyString());
        var resumable = states.getValue().resumeParameters();
        assertThat(resumable).isNotNull();
        assertThat(resumable.path("nextNode").asText()).isEqualTo("generate");
        assertThat(resumable.path("classification").isNull()).isTrue();
        assertThat(resumable.path("plan").isObject()).isTrue();
        assertThat(resumable.path("rounds").size()).isEqualTo(1);
        assertThat(resumable.path("latestGrade").isObject()).isTrue();
        assertThat(resumable.path("rewriteCount").asInt()).isZero();
        assertThat(resumable.path("runContext").isObject()).isTrue();
        assertThat(resumable.has("userId")).isFalse();
        assertThat(resumable.has("conversationId")).isFalse();
    }

    @Test
    void shouldResumeAtSavedNextNodeWithoutRepeatingCompletedWork() {
        AgenticRagRequest originalRequest = new AgenticRagRequest(
                "Redis MOVED 怎么处理", "history", null, "qwen-plus", "alice", 10L);
        QueryClassification classification = new QueryClassification(
                true, QuestionType.TROUBLESHOOTING, false, true, 0.95, "需要内部处置文档");
        RetrievalPlan retrievalPlan = plan("Redis MOVED", 20);
        RetrievalResult retrieval = new RetrievalResult(List.of(new RagHit(
                new KnowledgeChunk(7L, 3L, "runbook", "Redis 手册", "检查槽位。", 8, null),
                0.9, 0.8, 0.88)), 12L);
        AgentRunContext savedContext = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        try (TokenBudget.Reservation reservation = savedContext.reserve("retrieve", 20, 10, false)) {
            savedContext.commit("retrieve", reservation,
                    new com.zihan.zhiwei.ai.provider.dto.ProviderChatResponse(
                            "{}", "qwen-plus", "test", 7, 3, 10));
        }
        AgenticRagRequest savedRequest = originalRequest.withRunContext(savedContext);
        RagState savedState = RagState.restore(
                savedRequest, classification, retrievalPlan, null, 1, List.of(retrieval));
        AgenticRagCheckpointSnapshot snapshot = AgenticRagCheckpointSnapshot.capture(
                "grade", savedState, savedContext);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        CheckpointState checkpointState = new CheckpointState(
                1, "grade", List.of("classify", "plan", "retrieve"), List.of("grade"),
                List.of(), Map.of(), mapper.valueToTree(snapshot));
        AgentCheckpoint source = new AgentCheckpoint(
                9L, "run-fixed", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG, "grade",
                mapper.valueToTree(checkpointState), AgentCheckpoint.Status.FAILED, 3, 4L,
                null, "PROCESS_CRASH", LocalDateTime.now().plusDays(1),
                LocalDateTime.now(), LocalDateTime.now());
        AgentCheckpoint claimed = new AgentCheckpoint(
                9L, "run-fixed", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG, "grade",
                source.state(), AgentCheckpoint.Status.RUNNING, 3, 5L,
                null, null, source.expiresAt(), source.createdAt(), LocalDateTime.now());

        EvidenceGrade sufficient = new EvidenceGrade(true, List.of(7L), List.of("处置步骤"),
                List.of(), List.of(), NextAction.ANSWER, "证据充分");
        AgenticRagResult expected = new AgenticRagResult(true, "检查槽位 [E7]", List.of(),
                1, false, false, "ANSWERED", "test", "qwen-plus", 1, 1, 2, false, 10);
        when(evidenceGrader.grade(org.mockito.ArgumentMatchers.any())).thenReturn(sufficient);
        when(answerGenerator.generateAndVerify(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        CheckpointService checkpoints = mock(CheckpointService.class);
        when(checkpoints.get("alice", 9L)).thenReturn(java.util.Optional.of(source));
        when(checkpoints.resume("alice", 9L, 4L, "alice", "retry", "req-1")).thenReturn(claimed);
        when(checkpoints.updateProgress(eq("alice"), eq(9L), eq(5L), eq("generate"), any(), eq(4),
                eq("agentic-rag"), eq("node advanced"), anyString())).thenAnswer(invocation ->
                new AgentCheckpoint(9L, "run-fixed", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG,
                        "generate", mapper.valueToTree(invocation.getArgument(4)),
                        AgentCheckpoint.Status.RUNNING, 4, 6L, null, null, source.expiresAt(),
                        source.createdAt(), LocalDateTime.now()));
        MemoryProperties memory = new MemoryProperties();
        memory.setEnabled(true);
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);
        ReflectionTestUtils.setField(orchestrator, "checkpointService", checkpoints);
        ReflectionTestUtils.setField(orchestrator, "memoryProperties", memory);
        ConversationMapper conversations = mock(ConversationMapper.class);
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setUserId("alice");
        when(conversations.selectById(10L)).thenReturn(conversation);
        ReflectionTestUtils.setField(orchestrator, "conversationMapper", conversations);

        assertThat(orchestrator.resume("alice", 9L, 4L, "alice", "retry", "req-1"))
                .isSameAs(expected);
        verify(planner, never()).plan(org.mockito.ArgumentMatchers.any());
        verify(retriever, never()).retrieve(org.mockito.ArgumentMatchers.any());
        verify(evidenceGrader).grade(org.mockito.ArgumentMatchers.argThat(
                state -> state.getRounds().equals(List.of(retrieval))
                        && state.getRewriteCount() == 1
                        && state.getRequest().runContext().totalTokens() == 10
                        && state.getRequest().runContext().remainingTokens() == 490));
        verify(checkpoints).resume("alice", 9L, 4L, "alice", "retry", "req-1");
        verify(checkpoints).updateProgress(eq("alice"), eq(9L), eq(5L), eq("generate"), any(), eq(4),
                eq("agentic-rag"), eq("node advanced"), eq("agentic-rag:run-fixed:4"));
        verify(checkpoints, never()).create(any());
    }

    @Test
    void shouldRejectSupersededCheckpointFromLegacyMultiRowRun() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        RagState state = RagState.initial(new AgenticRagRequest(
                "question", null, null, "qwen-plus", context, "alice", 10L));
        AgenticRagCheckpointSnapshot snapshot = AgenticRagCheckpointSnapshot.capture("classify", state, context);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        CheckpointState checkpointState = new CheckpointState(
                1, "classify", List.of(), List.of("classify"), List.of(), Map.of(),
                mapper.valueToTree(snapshot));
        AgentCheckpoint source = new AgentCheckpoint(
                9L, "legacy-run", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG, "classify",
                mapper.valueToTree(checkpointState), AgentCheckpoint.Status.PAUSED, 0, 2L,
                null, null, LocalDateTime.now().plusDays(1), LocalDateTime.now(), LocalDateTime.now());
        CheckpointService checkpoints = mock(CheckpointService.class);
        when(checkpoints.get("alice", 9L)).thenReturn(java.util.Optional.of(source));
        when(checkpoints.hasLaterInRun("alice", "legacy-run", 0, 9L)).thenReturn(true);
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);
        ReflectionTestUtils.setField(orchestrator, "checkpointService", checkpoints);

        assertThatThrownBy(() -> orchestrator.resume("alice", 9L, 2L, "alice", "retry", "req"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("superseded");
        verify(checkpoints, never()).resume(anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void checkpointSnapshotRejectsGenerateWithoutSufficientAcceptedEvidence() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        QueryClassification classification = new QueryClassification(
                true, QuestionType.FACTUAL, false, false, 1, "needs evidence");
        EvidenceGrade insufficient = new EvidenceGrade(
                false, List.of(999L), List.of(), List.of("missing"), List.of(),
                NextAction.ANSWER, "forged");

        assertThatThrownBy(() -> new AgenticRagCheckpointSnapshot(
                1, "generate", "question", null, null, "qwen-plus",
                classification, plan("question", 20), List.of(RetrievalResult.empty()),
                insufficient, 0, context.snapshot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generate");
    }

    @Test
    void checkpointSnapshotRejectsRewriteCountAboveCurrentPolicy() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        AgenticRagCheckpointSnapshot snapshot = new AgenticRagCheckpointSnapshot(
                1, "classify", "question", null, null, "qwen-plus",
                null, null, List.of(), null, 3, context.snapshot());

        assertThatThrownBy(() -> snapshot.validatePolicy(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rewrite count");
    }

    @Test
    void shouldRejectResumeWhenSavedBudgetWasExhausted() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        RagState state = RagState.initial(new AgenticRagRequest(
                "question", null, null, "qwen-plus", context, "alice", 10L));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshot = AgenticRagCheckpointSnapshot.capture("classify", state, context);
        var checkpointState = new CheckpointState(
                1, "classify", List.of(), List.of("classify"), List.of(), Map.of(),
                mapper.valueToTree(snapshot));
        var source = new AgentCheckpoint(
                9L, "run", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG, "classify",
                mapper.valueToTree(checkpointState), AgentCheckpoint.Status.FAILED, 0, 2L,
                null, "TOKEN_BUDGET_EXHAUSTED", LocalDateTime.now().plusDays(1),
                LocalDateTime.now(), LocalDateTime.now());
        CheckpointService checkpoints = mock(CheckpointService.class);
        when(checkpoints.get("alice", 9L)).thenReturn(java.util.Optional.of(source));
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);
        ReflectionTestUtils.setField(orchestrator, "checkpointService", checkpoints);

        assertThatThrownBy(() -> orchestrator.resume("alice", 9L, 2L, "alice", "retry", "req"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-retryable");
        verify(checkpoints, never()).resume(anyString(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldRejectInconsistentCheckpointNodeMetadata() {
        AgentRunContext context = new AgentRunContext(
                new TokenBudget(500, 100, 10, Duration.ofSeconds(30)),
                new AgentNodeObserver(new SimpleMeterRegistry()));
        RagState state = RagState.initial(new AgenticRagRequest(
                "question", null, null, "qwen-plus", context, "alice", 10L));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshot = AgenticRagCheckpointSnapshot.capture("classify", state, context);
        var inconsistent = new CheckpointState(
                1, "plan", List.of(), List.of("plan"), List.of(), Map.of(), mapper.valueToTree(snapshot));
        var source = new AgentCheckpoint(
                9L, "run", 10L, "alice", AgentCheckpoint.Type.AGENTIC_RAG, "plan",
                mapper.valueToTree(inconsistent), AgentCheckpoint.Status.PAUSED, 0, 2L,
                null, null, LocalDateTime.now().plusDays(1), LocalDateTime.now(), LocalDateTime.now());
        CheckpointService checkpoints = mock(CheckpointService.class);
        when(checkpoints.get("alice", 9L)).thenReturn(java.util.Optional.of(source));
        ConversationMapper conversations = mock(ConversationMapper.class);
        Conversation conversation = new Conversation();
        conversation.setId(10L);
        conversation.setUserId("alice");
        when(conversations.selectById(10L)).thenReturn(conversation);
        AgenticRagOrchestrator orchestrator = new AgenticRagOrchestrator(
                planner, retriever, evidenceGrader, queryRewriter, answerGenerator, 2);
        ReflectionTestUtils.setField(orchestrator, "checkpointService", checkpoints);
        ReflectionTestUtils.setField(orchestrator, "conversationMapper", conversations);

        assertThatThrownBy(() -> orchestrator.resume("alice", 9L, 2L, "alice", "retry", "req"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node metadata");
    }

    private static RetrievalPlan plan(String query, int candidateK) {
        return new RetrievalPlan(List.of(new com.zihan.zhiwei.ai.rag.agentic.model.RetrievalTask(
                query,
                com.zihan.zhiwei.ai.rag.agentic.model.KnowledgeSource.INTERNAL_KB,
                com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy.HYBRID,
                Map.of())), 5, candidateK, "test");
    }
}
