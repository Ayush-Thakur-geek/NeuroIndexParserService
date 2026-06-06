package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Service
@Log4j2
public class KeywordExtractionServiceImpl implements KeyWordExtractionService {

    private final IndexWriter indexWriter;
    private final ExecutorService executorService;
    private static final FieldType BODY_FIELD_TYPE;

    static {

        BODY_FIELD_TYPE = new FieldType();

        BODY_FIELD_TYPE.setStored(true);

        BODY_FIELD_TYPE.setTokenized(true);

        BODY_FIELD_TYPE.setStoreTermVectors(true);

        BODY_FIELD_TYPE.setIndexOptions(
                IndexOptions.DOCS_AND_FREQS_AND_POSITIONS
        );

        BODY_FIELD_TYPE.freeze();
    }

    KeywordExtractionServiceImpl(
            IndexWriter indexWriter,
            ExecutorService executorService
    ) {
        this.indexWriter = indexWriter;
        this.executorService = executorService;
    }

    @Override
    public void extractingKeyWords(
            List<LuceneIndexDataDTO> batch
    ) {

        try (
                DirectoryReader directoryReader =
                        DirectoryReader.open(indexWriter)
        ) {

            IndexSearcher searcher =
                    new IndexSearcher(directoryReader);

            Map<Long, Integer> fragmentToDocIdMap =
                    new HashMap<>();

            for (int docId = 0; docId < directoryReader.maxDoc(); docId++) {

                Document doc =
                        directoryReader
                                .storedFields()
                                .document(docId);

                long fragmentId =
                        Long.parseLong(
                                doc.get(
                                        "semanticFragmentId_store"
                                )
                        );

                fragmentToDocIdMap.put(
                        fragmentId,
                        docId
                );
            }

            for (LuceneIndexDataDTO dto : batch) {
                Integer docId =
                        fragmentToDocIdMap.get(
                                dto.getSemanticFragmentId()
                        );

                if (docId == null) {
                    continue;
                }

                Terms terms =
                        directoryReader.termVectors()
                                .get(docId, "body");

                if (terms == null) {
                    continue;
                }

                TermsEnum termsEnum =
                        terms.iterator();

                BytesRef term;
                while ((term = termsEnum.next()) != null) {

                    String keyword =
                            term.utf8ToString();

                    long termFrequency =
                            termsEnum.totalTermFreq();

                    int documentFrequency =
                            directoryReader.docFreq(
                                    new Term(
                                            "body",
                                            keyword
                                    )
                            );

                    int totalDocuments =
                            directoryReader.numDocs();

                    double idf =
                            Math.log(
                                    1 +
                                            (
                                                    (totalDocuments
                                                            - documentFrequency
                                                            + 0.5)
                                                            /
                                                            (documentFrequency
                                                                    + 0.5)
                                            )
                            );

                    double score =
                            termFrequency * idf;

                    log.info(
                            "Keyword: {}, tf: {}, df: {}, score: {}",
                            keyword,
                            termFrequency,
                            documentFrequency,
                            score
                    );
                }
            }


        } catch (Exception e) {

            log.error(
                    "Error extracting keywords from index doc: {}",
                    e.getMessage(),
                    e
            );

            throw new CustomException(
                    e.getMessage(),
                    "KEYWORD_EXTRACTION_ERROR",
                    500,
                    e
            );
        }
    }

    @Override
    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO) {
        try {
            Document document = new Document();
            document.add(
                    new LongPoint("userId",
                            luceneIndexDataDTO.getUserId())
            );

            document.add(
                    new LongPoint("llmId",
                            luceneIndexDataDTO.getLlmId())
            );

            document.add(
                    new LongPoint("affiliatedEmailId",
                            luceneIndexDataDTO.getAffiliatedEmailId())
            );

            document.add(
                    new LongPoint("conversationId",
                            luceneIndexDataDTO.getConversationId())
            );

            document.add(
                    new LongPoint("messageId",
                            luceneIndexDataDTO.getMessageId())
            );

            document.add(
                    new LongPoint("semanticFragmentId",
                            luceneIndexDataDTO.getSemanticFragmentId())
            );

            document.add(
                    new StoredField("userId_store",
                            luceneIndexDataDTO.getUserId())
            );

            document.add(
                    new StoredField("llmId_store",
                            luceneIndexDataDTO.getLlmId())
            );

            document.add(
                    new StoredField("affiliatedEmailId_store",
                            luceneIndexDataDTO.getAffiliatedEmailId())
            );

            document.add(
                    new StoredField("conversationId_store",
                            luceneIndexDataDTO.getConversationId())
            );

            document.add(
                    new StoredField("messageId_store",
                            luceneIndexDataDTO.getMessageId())
            );

            document.add(
                    new StoredField("semanticFragmentId_store",
                            luceneIndexDataDTO.getSemanticFragmentId())
            );

            document.add(
                    new LongPoint(
                            "timestamp",
                            System.currentTimeMillis()
                    )
            );

            document.add(
                    new StoredField(
                            "timestamp_store",
                            System.currentTimeMillis()
                    )
            );

            document.add(
                    new Field(
                            "body",
                            luceneIndexDataDTO.getText(),
                            BODY_FIELD_TYPE
                    )
            );
            indexWriter.addDocument(document);
            indexWriter.commit();

        } catch (IOException e) {
            log.error("Error while opening the index doc: {}", e);
            throw new CustomException(
                    e.getMessage(),
                    "DOC_OPENEING_ERROR",
                    500,
                    e
            );
        }
    }
}
