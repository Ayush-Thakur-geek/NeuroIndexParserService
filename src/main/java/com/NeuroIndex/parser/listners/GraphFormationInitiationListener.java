package com.NeuroIndex.parser.listners;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class GraphFormationInitiationListener {

    @Async("keywordExtractionExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handleGraphFormationInitiationEvent(
            KeywordExtractionListener.GraphFormationInitiationEvent event
    ) {
        Long userId = event.userId();
    }
}
