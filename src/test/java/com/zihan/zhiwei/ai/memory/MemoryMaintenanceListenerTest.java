package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemoryFactVersion;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryMaintenanceListenerTest {
    private final MemoryProperties properties = new MemoryProperties();
    private final ConversationService conversations = mock(ConversationService.class);
    private final MemorySummaryService summaries = mock(MemorySummaryService.class);
    private final MemorySummarizer summarizer = mock(MemorySummarizer.class);
    private final MemoryFactExtractor extractor = mock(MemoryFactExtractor.class);
    private final MemoryFactService facts = mock(MemoryFactService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MemoryMaintenanceListener listener;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.getSummary().setMessageThreshold(8);
        properties.getSummary().setTokenThreshold(3000);
        properties.getSummary().setRecentMessagesToKeep(4);
        listener = new MemoryMaintenanceListener(properties, conversations, summaries,
                summarizer, extractor, facts, meters);
    }

    @Test
    void belowBothThresholdsDoesNotCallModels() {
        when(summaries.get("u1", 9L)).thenReturn(Optional.empty());
        when(conversations.listMessagesAfter("u1", 9L, 0L, 100)).thenReturn(messages(1, 7, "small"));

        listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 6L, 7L));

        verifyNoInteractions(summarizer, extractor);
        verify(summaries, never()).save(anyString(), anyLong(), any());
    }

    @Test
    void thresholdSummarizesOnlyUncoveredMessagesAndKeepsRecentMessagesRaw() {
        MemorySummary current = summary(10L, 2L, 3L);
        when(summaries.get("u1", 9L)).thenReturn(Optional.of(current));
        when(conversations.listMessagesAfter("u1", 9L, 10L, 100))
                .thenReturn(messages(11, 18, "content"));
        when(summarizer.summarize(eq("old"), anyList()))
                .thenReturn(new MemorySummarizer.Result("new", List.of(), List.of(), List.of()));

        listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 17L, 18L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> input = ArgumentCaptor.forClass(List.class);
        verify(summarizer).summarize(eq("old"), input.capture());
        assertThat(input.getValue()).extracting(Message::getId).containsExactly(11L, 12L, 13L, 14L);
        ArgumentCaptor<MemorySummaryService.SummaryWrite> write =
                ArgumentCaptor.forClass(MemorySummaryService.SummaryWrite.class);
        verify(summaries).save(eq("u1"), eq(9L), write.capture());
        assertThat(write.getValue().coveredThroughMessageId()).isEqualTo(14L);
        assertThat(write.getValue().expectedVersion()).isEqualTo(3L);
        assertThat(write.getValue().sourceMessageCount()).isEqualTo(6);
    }

    @Test
    void tokenThresholdAlsoTriggersSummary() {
        properties.getSummary().setMessageThreshold(99);
        properties.getSummary().setTokenThreshold(10);
        properties.getSummary().setRecentMessagesToKeep(1);
        when(summaries.get("u1", 9L)).thenReturn(Optional.empty());
        when(conversations.listMessagesAfter("u1", 9L, 0L, 100))
                .thenReturn(List.of(message(1, "user", "1234567890123456789012345678901234567890"),
                        message(2, "assistant", "done")));
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new MemorySummarizer.Result("new", List.of(), List.of(), List.of()));

        listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 1L, 2L));

        verify(summarizer).summarize(isNull(), argThat(messages -> messages.size() == 1));
    }

    @Test
    void invalidModelOutputLeavesExistingSummaryUntouchedAndFailureIsIsolated() {
        when(summaries.get("u1", 9L)).thenReturn(Optional.of(summary(10L, 2L, 3L)));
        when(conversations.listMessagesAfter("u1", 9L, 10L, 100)).thenReturn(messages(11, 18, "content"));
        when(summarizer.summarize(any(), anyList())).thenThrow(new IllegalArgumentException("invalid structured output"));

        assertThatCode(() -> listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 17L, 18L)))
                .doesNotThrowAnyException();

        verify(summaries, never()).save(anyString(), anyLong(), any());
        assertThat(meters.counter("memory.maintenance", "result", "failure").count()).isEqualTo(1);
    }

    @Test
    void casConflictReloadsAndRetriesAtMostOnce() {
        when(summaries.get("u1", 9L)).thenReturn(Optional.of(summary(10L, 2L, 3L)),
                Optional.of(summary(10L, 4L, 4L)));
        when(conversations.listMessagesAfter(eq("u1"), eq(9L), anyLong(), eq(100)))
                .thenAnswer(inv -> messages(inv.<Long>getArgument(2).intValue() + 1, 18, "content"));
        when(summarizer.summarize(any(), anyList()))
                .thenReturn(new MemorySummarizer.Result("new", List.of(), List.of(), List.of()));
        when(summaries.save(anyString(), anyLong(), any()))
                .thenThrow(new MemoryVersionConflictException(3L))
                .thenThrow(new MemoryVersionConflictException(4L));

        assertThatCode(() -> listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 17L, 18L)))
                .doesNotThrowAnyException();

        verify(summaries, times(2)).save(anyString(), anyLong(), any());
        verify(summarizer, times(2)).summarize(any(), anyList());
    }

    @Test
    void extractionCreatesOnlyAgentExtractedProposedFacts() {
        properties.setFactExtractionEnabled(true);
        when(summaries.get("u1", 9L)).thenReturn(Optional.empty());
        when(conversations.listMessagesAfter("u1", 9L, 0L, 100))
                .thenReturn(messages(1, 2, "short"));
        var extracted = new MemoryFactExtractor.ExtractedFact("profile", "u1", "timezone",
                new ObjectMapper().getNodeFactory().textNode("Asia/Shanghai"), new BigDecimal("0.9"));
        when(extractor.extract(anyList())).thenReturn(List.of(extracted));
        when(facts.list(eq("u1"), any())).thenReturn(List.of());

        listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 1L, 2L));

        ArgumentCaptor<MemoryFactService.FactWrite> write = ArgumentCaptor.forClass(MemoryFactService.FactWrite.class);
        verify(facts).create(eq("u1"), write.capture());
        assertThat(write.getValue().sourceType()).isEqualTo(MemoryFact.SourceType.AGENT_EXTRACTED);
        verify(facts, never()).update(anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void extractionUsesProposalForAnExistingFactAndNeverActivatesIt() {
        properties.setFactExtractionEnabled(true);
        when(summaries.get("u1", 9L)).thenReturn(Optional.empty());
        when(conversations.listMessagesAfter("u1", 9L, 0L, 100)).thenReturn(messages(1, 2, "short"));
        var extracted = new MemoryFactExtractor.ExtractedFact("profile", "u1", "timezone",
                new ObjectMapper().getNodeFactory().textNode("UTC"), new BigDecimal("0.8"));
        when(extractor.extract(anyList())).thenReturn(List.of(extracted));
        MemoryFact fact = new MemoryFact(5L, "u1", "profile", "u1", "timezone", 8L,
                MemoryFact.State.ACTIVE, 7L, LocalDateTime.now(), LocalDateTime.now());
        MemoryFactVersion version = new MemoryFactVersion(8L, 5L, 1,
                new ObjectMapper().getNodeFactory().textNode("CST"), "hash", MemoryFact.SourceType.USER,
                null, null, null, null, LocalDateTime.now(), "u1", "explicit");
        when(facts.list(eq("u1"), any())).thenReturn(List.of(new MemoryFactService.FactView(fact, version)));

        listener.onCompleted(new ConversationTurnCompletedEvent("u1", 9L, 1L, 2L));

        verify(facts).proposeCandidate(eq("u1"), eq(5L), eq(7L),
                argThat(write -> write.sourceType() == MemoryFact.SourceType.AGENT_EXTRACTED));
        verify(facts, never()).update(anyString(), anyLong(), anyLong(), any());
    }

    private static MemorySummary summary(long covered, long count, long version) {
        return new MemorySummary(1L, 9L, "u1", "old", List.of(), List.of(), List.of(),
                covered, (int) count, version, MemorySummary.Status.ACTIVE, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static List<Message> messages(int first, int last, String content) {
        java.util.ArrayList<Message> result = new java.util.ArrayList<>();
        for (int i = first; i <= last; i++) result.add(message(i, i % 2 == 0 ? "assistant" : "user", content));
        return result;
    }

    private static Message message(long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
