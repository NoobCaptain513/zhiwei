package com.zihan.zhiwei.ai.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.*;

class ConversationTurnCompletedPublisherTest {
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ConversationTurnCompletedPublisher publisher = new ConversationTurnCompletedPublisher(events);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var event = new ConversationTurnCompletedEvent("u1", 1L, 2L, 3L);

        publisher.publishAfterCommit(event);
        verifyNoInteractions(events);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(events).publishEvent(event);
    }

    @Test
    void rollbackNeverPublishes() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        publisher.publishAfterCommit(new ConversationTurnCompletedEvent("u1", 1L, 2L, 3L));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(events);
    }

    @Test
    void refusesToPublishOutsideATransaction() {
        publisher.publishAfterCommit(new ConversationTurnCompletedEvent("u1", 1L, 2L, 3L));
        verifyNoInteractions(events);
    }
}
