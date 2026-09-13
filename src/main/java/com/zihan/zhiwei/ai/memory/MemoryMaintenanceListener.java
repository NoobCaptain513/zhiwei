package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemorySummary;
import com.zihan.zhiwei.common.exception.MemoryVersionConflictException;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.service.ConversationService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Best-effort, post-commit memory maintenance. No failure escapes this boundary. */
@Slf4j
@Component
public class MemoryMaintenanceListener {
    private static final int READ_LIMIT = 100;
    private static final String ACTOR = "memory-maintenance";

    private final MemoryProperties properties;
    private final ConversationService conversations;
    private final MemorySummaryService summaries;
    private final MemorySummarizer summarizer;
    private final MemoryFactExtractor extractor;
    private final MemoryFactService facts;
    private final MeterRegistry meters;
    private final AtomicInteger summaryLag = new AtomicInteger();

    public MemoryMaintenanceListener(MemoryProperties properties, ConversationService conversations,
                                     MemorySummaryService summaries, MemorySummarizer summarizer,
                                     MemoryFactExtractor extractor, MemoryFactService facts,
                                     MeterRegistry meters) {
        this.properties = properties;
        this.conversations = conversations;
        this.summaries = summaries;
        this.summarizer = summarizer;
        this.extractor = extractor;
        this.facts = facts;
        this.meters = meters;
        meters.gauge("memory.summary.lag.messages", summaryLag);
    }

    @Async
    @EventListener
    public void onCompleted(ConversationTurnCompletedEvent event) {
        if (!properties.isEnabled()) return;
        boolean failed = false;
        try {
            maintainSummary(event);
        } catch (Exception failure) {
            failed = true;
            log.warn("Rolling summary maintenance failed for conversationId={}: {}",
                    event.conversationId(), failure.getMessage());
        }
        if (properties.isFactExtractionEnabled()) {
            try {
                extractFacts(event);
            } catch (Exception failure) {
                failed = true;
                log.warn("Memory fact extraction failed for conversationId={}: {}",
                        event.conversationId(), failure.getMessage());
            }
        }
        meters.counter("memory.maintenance", "result", failed ? "failure" : "success").increment();
    }

    private void maintainSummary(ConversationTurnCompletedEvent event) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<MemorySummary> current = summaries.get(event.userId(), event.conversationId());
            long covered = current.map(MemorySummary::coveredThroughMessageId).orElse(0L);
            List<Message> uncovered = conversations.listMessagesAfter(
                            event.userId(), event.conversationId(), covered, READ_LIMIT).stream()
                    .filter(message -> message.getId() != null && message.getId() <= event.assistantMessageId())
                    .toList();
            summaryLag.set(uncovered.size());
            if (!thresholdReached(uncovered)) return;

            int keep = Math.max(0, properties.getSummary().getRecentMessagesToKeep());
            int summarizeCount = uncovered.size() - keep;
            if (summarizeCount <= 0) return;
            List<Message> input = List.copyOf(uncovered.subList(0, summarizeCount));
            MemorySummarizer.Result generated = summarizer.summarize(
                    current.map(MemorySummary::summary).orElse(null), input);
            Message last = input.getLast();
            int sourceCount = current.map(MemorySummary::sourceMessageCount).orElse(0) + input.size();
            var write = new MemorySummaryService.SummaryWrite(
                    generated.summary(), generated.openLoops(), generated.decisions(), generated.entities(),
                    last.getId(), sourceCount, current.map(MemorySummary::version).orElse(null),
                    null, ACTOR, "rolling conversation summary", "turn:" + event.assistantMessageId());
            try {
                summaries.save(event.userId(), event.conversationId(), write);
                summaryLag.set(keep);
                return;
            } catch (MemoryVersionConflictException conflict) {
                if (attempt == 1) throw conflict;
            }
        }
    }

    private boolean thresholdReached(List<Message> uncovered) {
        if (uncovered.size() >= properties.getSummary().getMessageThreshold()) return true;
        long characters = uncovered.stream().map(Message::getContent)
                .filter(java.util.Objects::nonNull).mapToLong(String::length).sum();
        long estimatedTokens = (characters + 3) / 4;
        return estimatedTokens >= properties.getSummary().getTokenThreshold();
    }

    private void extractFacts(ConversationTurnCompletedEvent event) {
        List<Message> turn = conversations.listMessagesAfter(event.userId(), event.conversationId(),
                        event.userMessageId() - 1, READ_LIMIT).stream()
                .filter(message -> message.getId() != null
                        && message.getId() >= event.userMessageId()
                        && message.getId() <= event.assistantMessageId())
                .toList();
        if (turn.isEmpty()) return;
        for (MemoryFactExtractor.ExtractedFact extracted : extractor.extract(turn)) {
            var filter = new MemoryFactService.FactFilter(extracted.namespace(), extracted.subject(),
                    extracted.predicate(), null, 1);
            List<MemoryFactService.FactView> existing = facts.list(event.userId(), filter);
            var write = new MemoryFactService.FactWrite(
                    extracted.namespace(), extracted.subject(), extracted.predicate(), extracted.value(),
                    MemoryFact.SourceType.AGENT_EXTRACTED,
                    "message:" + event.assistantMessageId(), extracted.confidence(), null, null,
                    ACTOR, "model-extracted candidate", "turn:" + event.assistantMessageId());
            if (existing.isEmpty()) {
                facts.create(event.userId(), write);
            } else {
                MemoryFact fact = existing.getFirst().fact();
                facts.proposeCandidate(event.userId(), fact.id(), fact.version(), write);
            }
        }
    }
}
