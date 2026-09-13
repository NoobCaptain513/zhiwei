package com.zihan.zhiwei.ai.memory.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.knowledge.TokenCounter;
import com.zihan.zhiwei.ai.memory.MemoryContext;
import com.zihan.zhiwei.ai.memory.MemoryContextService;
import com.zihan.zhiwei.ai.memory.MemoryFactService;
import com.zihan.zhiwei.ai.memory.MemoryProperties;
import com.zihan.zhiwei.ai.memory.MemorySummaryService;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.mapper.AgentCheckpointMapper;
import com.zihan.zhiwei.pojo.entity.AgentCheckpointEntity;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemoryContextServiceImpl implements MemoryContextService {
    private static final int MESSAGE_READ_LIMIT = 100;

    private final MemorySummaryService summaryService;
    private final MemoryFactService factService;
    private final ConversationService conversationService;
    private final AgentCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;
    private final MemoryProperties properties;

    @Override
    public MemoryContext buildContext(String userId, long conversationId, String currentUserMessage, int tokenBudget) {
        requireRequest(userId, conversationId, currentUserMessage, tokenBudget);
        // A supplied ID never creates a conversation; this call establishes ownership before any memory read.
        conversationService.getOrCreate(userId, conversationId);
        LocalDateTime now = LocalDateTime.now();
        int used = tokenCounter.count(currentUserMessage);
        if (used > tokenBudget) {
            throw new IllegalArgumentException("current user message exceeds token budget");
        }

        Optional<MemorySummary> availableSummary = summaryService.get(userId, conversationId)
                .filter(summary -> isUsableSummary(summary, userId, conversationId, now));
        long coveredThrough = availableSummary.map(MemorySummary::coveredThroughMessageId).orElse(0L);

        List<Message> availableMessages = new ArrayList<>(conversationService.listMessagesAfter(
                userId, conversationId, coveredThrough, MESSAGE_READ_LIMIT));
        availableMessages.removeIf(message -> message == null || !conversationIdEquals(message, conversationId));
        availableMessages.sort(Comparator.comparing(Message::getId, Comparator.nullsLast(Long::compareTo)));
        omitPersistedCurrentMessage(availableMessages, currentUserMessage);

        Optional<AgentCheckpoint> availableCheckpoint = latestResumableCheckpoint(userId, conversationId, now);
        List<MemoryFactService.FactView> availableFacts = factService.list(userId,
                        new MemoryFactService.FactFilter(null, null, null, MemoryFact.State.ACTIVE, properties.getFactLimit()))
                .stream()
                .filter(view -> isUsableFact(view, userId, now))
                .limit(properties.getFactLimit())
                .toList();

        Optional<AgentCheckpoint> checkpoint = Optional.empty();
        if (availableCheckpoint.isPresent()) {
            int cost = checkpointTokens(availableCheckpoint.orElseThrow());
            if (used + cost <= tokenBudget) {
                checkpoint = availableCheckpoint;
                used += cost;
            }
        }

        List<Message> messages = new ArrayList<>();
        for (int i = availableMessages.size() - 1; i >= 0; i--) {
            Message message = availableMessages.get(i);
            int cost = messageTokens(message);
            if (used + cost <= tokenBudget) {
                messages.add(message);
                used += cost;
            }
        }
        messages.sort(Comparator.comparing(Message::getId, Comparator.nullsLast(Long::compareTo)));

        List<MemoryFactService.FactView> selectedFacts = new ArrayList<>();
        for (MemoryFactService.FactView fact : availableFacts) {
            int cost = factTokens(fact);
            if (used + cost <= tokenBudget) {
                selectedFacts.add(fact);
                used += cost;
            }
        }

        Optional<MemorySummary> summary = Optional.empty();
        if (availableSummary.isPresent()) {
            int cost = summaryTokens(availableSummary.orElseThrow());
            if (used + cost <= tokenBudget) {
                summary = availableSummary;
                used += cost;
            }
        }

        return new MemoryContext(currentUserMessage, summary, checkpoint, selectedFacts, messages, used, tokenBudget);
    }

    private Optional<AgentCheckpoint> latestResumableCheckpoint(String userId, long conversationId,
                                                                  LocalDateTime now) {
        List<AgentCheckpointEntity> rows = checkpointMapper.selectList(
                new LambdaQueryWrapper<AgentCheckpointEntity>()
                        .eq(AgentCheckpointEntity::getUserId, userId)
                        .eq(AgentCheckpointEntity::getConversationId, conversationId)
                        .in(AgentCheckpointEntity::getStatus,
                                AgentCheckpoint.Status.PAUSED.name(), AgentCheckpoint.Status.FAILED.name())
                        .and(query -> query.isNull(AgentCheckpointEntity::getExpiresAt)
                                .or().gt(AgentCheckpointEntity::getExpiresAt, now))
                        .orderByDesc(AgentCheckpointEntity::getUpdatedAt)
                        .orderByDesc(AgentCheckpointEntity::getId)
                        .last("LIMIT 10"));
        if (rows == null) return Optional.empty();
        return rows.stream()
                .filter(row -> isResumableCheckpoint(row, userId, conversationId, now))
                .max(Comparator.comparing(AgentCheckpointEntity::getUpdatedAt,
                                Comparator.nullsFirst(LocalDateTime::compareTo))
                        .thenComparing(AgentCheckpointEntity::getId, Comparator.nullsFirst(Long::compareTo)))
                .map(this::toCheckpoint);
    }

    private boolean isResumableCheckpoint(AgentCheckpointEntity row, String userId, long conversationId,
                                           LocalDateTime now) {
        if (row == null || !userId.equals(row.getUserId()) || row.getConversationId() == null
                || row.getConversationId() != conversationId || Integer.valueOf(1).equals(row.getIsDeleted())) {
            return false;
        }
        AgentCheckpoint.Status status;
        try {
            status = AgentCheckpoint.Status.valueOf(row.getStatus());
        } catch (RuntimeException ignored) {
            return false;
        }
        if (status != AgentCheckpoint.Status.PAUSED && status != AgentCheckpoint.Status.FAILED) return false;
        if (row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now)) return false;
        return status != AgentCheckpoint.Status.FAILED || row.getResumeAfter() == null || !row.getResumeAfter().isAfter(now);
    }

    private AgentCheckpoint toCheckpoint(AgentCheckpointEntity row) {
        try {
            return new AgentCheckpoint(row.getId(), row.getRunId(), row.getConversationId(), row.getUserId(),
                    AgentCheckpoint.Type.valueOf(row.getCheckpointType()), row.getNodeName(),
                    objectMapper.readTree(row.getStateJson()), AgentCheckpoint.Status.valueOf(row.getStatus()),
                    row.getSequenceNo(), row.getVersion(), row.getResumeAfter(), row.getErrorCode(),
                    row.getExpiresAt(), row.getCreatedAt(), row.getUpdatedAt());
        } catch (Exception e) {
            throw new IllegalStateException("invalid stored checkpoint", e);
        }
    }

    private boolean isUsableSummary(MemorySummary summary, String userId, long conversationId, LocalDateTime now) {
        return userId.equals(summary.userId()) && summary.conversationId() != null
                && summary.conversationId() == conversationId && summary.status() == MemorySummary.Status.ACTIVE
                && (summary.expiresAt() == null || summary.expiresAt().isAfter(now));
    }

    private boolean isUsableFact(MemoryFactService.FactView view, String userId, LocalDateTime now) {
        if (view == null || view.fact() == null || view.currentVersion() == null) return false;
        MemoryFact fact = view.fact();
        var version = view.currentVersion();
        return userId.equals(fact.userId()) && fact.state() == MemoryFact.State.ACTIVE
                && fact.currentVersionId() != null && fact.currentVersionId().equals(version.id())
                && (version.validFrom() == null || !version.validFrom().isAfter(now))
                && (version.validTo() == null || version.validTo().isAfter(now));
    }

    private void omitPersistedCurrentMessage(List<Message> messages, String currentUserMessage) {
        if (messages.isEmpty()) return;
        Message latest = messages.getLast();
        if ("user".equalsIgnoreCase(latest.getRole()) && currentUserMessage.equals(latest.getContent())) {
            messages.removeLast();
        }
    }

    private int checkpointTokens(AgentCheckpoint checkpoint) {
        return tokenCounter.count(nullToEmpty(checkpoint.runId()))
                + tokenCounter.count(nullToEmpty(checkpoint.nodeName()))
                + tokenCounter.count(checkpoint.state() == null ? "" : checkpoint.state().toString())
                + tokenCounter.count(checkpoint.status().name());
    }

    private int messageTokens(Message message) {
        return tokenCounter.count(nullToEmpty(message.getRole())) + tokenCounter.count(nullToEmpty(message.getContent()));
    }

    private int factTokens(MemoryFactService.FactView view) {
        MemoryFact fact = view.fact();
        return tokenCounter.count(nullToEmpty(fact.namespace())) + tokenCounter.count(nullToEmpty(fact.subject()))
                + tokenCounter.count(nullToEmpty(fact.predicate()))
                + tokenCounter.count(view.currentVersion().value().toString());
    }

    private int summaryTokens(MemorySummary summary) {
        int tokens = tokenCounter.count(nullToEmpty(summary.summary()));
        tokens += listTokens(summary.openLoops());
        tokens += listTokens(summary.decisions());
        tokens += listTokens(summary.entities());
        return tokens;
    }

    private int listTokens(List<String> values) {
        if (values == null) return 0;
        return values.stream().mapToInt(value -> tokenCounter.count(nullToEmpty(value))).sum();
    }

    private static boolean conversationIdEquals(Message message, long conversationId) {
        return message.getConversationId() != null && message.getConversationId() == conversationId;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireRequest(String userId, long conversationId, String message, int tokenBudget) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (conversationId <= 0) throw new IllegalArgumentException("conversationId must be positive");
        if (message == null) throw new IllegalArgumentException("currentUserMessage is required");
        if (tokenBudget < 0) throw new IllegalArgumentException("tokenBudget cannot be negative");
    }
}
