package com.zihan.zhiwei.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.*;
import com.zihan.zhiwei.ai.memory.model.*;
import com.zihan.zhiwei.common.exception.GlobalExceptionHandler;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.pojo.dto.memory.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MemoryControllerTest {
    private final MemorySummaryService summaries = mock(MemorySummaryService.class);
    private final CheckpointService checkpoints = mock(CheckpointService.class);
    private final MemoryFactService facts = mock(MemoryFactService.class);
    private final MemoryAuditService audits = mock(MemoryAuditService.class);
    private final MemoryForgetService forget = mock(MemoryForgetService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new MemoryController(summaries, checkpoints, facts, audits, Optional.of(forget),
                new MemoryOwnerResolver());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSummaryReturnsEtagAndResultEnvelope() throws Exception {
        when(summaries.get("alice", 9L)).thenReturn(Optional.of(summary(3L)));

        mvc.perform(get("/api/memories/conversations/9/summary").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.summary.conversationId").value(9));
    }

    @Test
    void authenticatedOwnerCannotDelegateToAnotherUser() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());

        mvc.perform(get("/api/memories/conversations/9/summary")
                        .param("userId", "bob").principal(auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(summaries);
    }

    @Test
    void summaryValidationFailureIsBadRequest() throws Exception {
        mvc.perform(put("/api/memories/conversations/9/summary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"summary\":\"\",\"coveredThroughMessageId\":1," +
                                "\"sourceMessageCount\":0,\"actorId\":\"alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void factPatchWithoutIfMatchIsPreconditionRequired() throws Exception {
        mvc.perform(patch("/api/memories/facts/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factJson("alice")))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value(428));
        verify(facts, never()).update(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void versionConflictIsConflict() throws Exception {
        when(facts.get("alice", 4L)).thenReturn(Optional.of(factView(2L)));
        when(facts.update(eq("alice"), eq(4L), eq(2L), any()))
                .thenThrow(new MemoryVersionConflictException(2L));

        mvc.perform(patch("/api/memories/facts/4")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(factJson("alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void checkpointCreateUsesAuthenticatedOwner() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
        when(checkpoints.create(any())).thenAnswer(invocation -> checkpoint(invocation.getArgument(0)));

        mvc.perform(post("/api/memories/checkpoints").principal(auth)
                        .header("Idempotency-Key", "idem-1")
                        .header("X-Request-Id", "request-1")
                        .param("actorId", "alice").param("reason", "save progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"runId\":\"run-1\",\"conversationId\":9," +
                                "\"checkpointType\":\"AGENT\",\"nodeName\":\"plan\"," +
                                "\"state\":{\"schemaVersion\":1,\"currentNode\":\"plan\"}," +
                                "\"status\":\"RUNNING\",\"sequenceNo\":0}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""));

        verify(checkpoints).create(argThat(c -> c.userId().equals("alice") && c.requestId().equals("request-1")));
    }

    @Test
    void versionsAndConflictsAreOwnerScoped() throws Exception {
        when(facts.get("alice", 4L)).thenReturn(Optional.of(factView(2L)));
        when(facts.versions("alice", 4L)).thenReturn(List.of());
        when(facts.conflicts("alice", 4L)).thenReturn(List.of());

        mvc.perform(get("/api/memories/facts/4/versions").param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mvc.perform(get("/api/memories/facts/4/conflicts").param("userId", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void auditLimitIsBoundedAndListingDelegatesOwner() throws Exception {
        mvc.perform(get("/api/memories/audit").param("userId", "alice").param("limit", "101"))
                .andExpect(status().isBadRequest());

        when(audits.list(eq("alice"), any())).thenReturn(List.of());
        mvc.perform(get("/api/memories/audit").param("userId", "alice")
                        .param("resourceType", "FACT").param("resourceId", "4").param("limit", "25"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(audits).list(eq("alice"), argThat(f -> f.limit() == 25 && "4".equals(f.resourceId())));
    }

    @Test
    void forgetEndpointsReturnAcceptedAndOwnerScopedStatus() throws Exception {
        var job = new MemoryForgetJob("job-1", "alice", MemoryForgetJob.ScopeType.FACT, "4",
                MemoryForgetJob.Status.PENDING, "alice", "privacy", LocalDateTime.now(), null, null);
        when(forget.create(eq("alice"), any(), eq("idem-1"), eq("request-1"))).thenReturn(job);
        when(forget.get("alice", "job-1")).thenReturn(Optional.of(job));

        mvc.perform(post("/api/memories/forget")
                        .header("Idempotency-Key", "idem-1").header("X-Request-Id", "request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\",\"scopeType\":\"FACT\",\"scopeId\":\"4\"," +
                                "\"requestedBy\":\"alice\",\"reason\":\"privacy\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.job.jobId").value("job-1"));

        mvc.perform(get("/api/memories/forget/job-1").param("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.userId").value("alice"));
    }

    private MemorySummary summary(long version) {
        return new MemorySummary(1L, 9L, "alice", "summary", List.of(), List.of(), List.of(),
                7L, 2, version, MemorySummary.Status.ACTIVE, null, LocalDateTime.now(), LocalDateTime.now());
    }

    private MemoryFactService.FactView factView(long version) throws Exception {
        var fact = new MemoryFact(4L, "alice", "profile", "alice", "timezone", 8L,
                MemoryFact.State.ACTIVE, version, LocalDateTime.now(), LocalDateTime.now());
        var factVersion = new MemoryFactVersion(8L, 4L, 1, objectMapper.readTree("\"UTC\""), "hash",
                MemoryFact.SourceType.USER, null, null, null, null, LocalDateTime.now(), "alice", "create");
        return new MemoryFactService.FactView(fact, factVersion);
    }

    private AgentCheckpoint checkpoint(CheckpointService.CreateCommand command) throws Exception {
        return new AgentCheckpoint(5L, command.runId(), command.conversationId(), command.userId(),
                command.checkpointType(), command.nodeName(), objectMapper.valueToTree(command.state()), command.status(),
                command.sequenceNo(), 1L, command.resumeAfter(), command.errorCode(), LocalDateTime.now().plusDays(1),
                LocalDateTime.now(), LocalDateTime.now());
    }

    private String factJson(String userId) {
        return "{\"userId\":\"" + userId + "\",\"namespace\":\"profile\",\"subject\":\"alice\"," +
                "\"predicate\":\"timezone\",\"value\":\"UTC\",\"sourceType\":\"USER\"," +
                "\"actorId\":\"alice\",\"reason\":\"update\"}";
    }
}
