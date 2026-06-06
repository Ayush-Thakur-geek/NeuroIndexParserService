package com.NeuroIndex.parser.listners;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import com.NeuroIndex.parser.service.impl.ClaudeIngestionServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
@RequiredArgsConstructor
@Log4j2
public class KeywordExtractionListener {

    private final KeyWordExtractionService keyWordExtractionService;
    private static final int BATCH_SIZE = 50;

    @Async("keywordExtractionExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void handleKeywordExtraction(
            ClaudeIngestionServiceImpl.KeywordExtractionEvent event
    ) {

        List<LuceneIndexDataDTO> tasks = event.tasks();

        for (int i = 0; i < tasks.size(); i += BATCH_SIZE) {

            int end =
                    Math.min(i + BATCH_SIZE, tasks.size());

            List<LuceneIndexDataDTO> batch =
                    tasks.subList(i, end);

            try {

                keyWordExtractionService
                        .extractingKeyWords(batch);

            } catch (Exception e) {

                log.error(
                        "Keyword extraction batch failed",
                        e
                );
            }
        }
    }
}
