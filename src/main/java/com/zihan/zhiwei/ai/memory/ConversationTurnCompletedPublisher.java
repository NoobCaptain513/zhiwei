package com.zihan.zhiwei.ai.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registers publication against the transaction that persisted the assistant completion. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTurnCompletedPublisher {
    private final ApplicationEventPublisher events;

    public void publishAfterCommit(ConversationTurnCompletedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Skipping conversation completion event outside a transaction: conversationId={}",
                    event.conversationId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    events.publishEvent(event);
                } catch (RuntimeException failure) {
                    // The response transaction is already committed; maintenance publication is best effort.
                    log.error("Unable to publish memory maintenance event for conversationId={}",
                            event.conversationId(), failure);
                }
            }
        });
    }
}
