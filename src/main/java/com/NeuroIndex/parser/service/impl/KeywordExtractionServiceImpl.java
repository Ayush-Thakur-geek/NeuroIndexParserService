package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.SemanticFragment;
import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@Log4j2
public class KeywordExtractionServiceImpl implements KeyWordExtractionService {

    private final IndexWriter indexWriter;
    private static final FieldType BODY_FIELD_TYPE;

    static {
        BODY_FIELD_TYPE = new FieldType();

        BODY_FIELD_TYPE.setStored(true);
        BODY_FIELD_TYPE.setTokenized(true);
        BODY_FIELD_TYPE.setStoreTermVectors(true);

        BODY_FIELD_TYPE.freeze();
    }

    KeywordExtractionServiceImpl(IndexWriter indexWriter) {
        this.indexWriter = indexWriter;
    }

    @Override
    public void extractingAndIndexing(LuceneIndexDataDTO luceneIndexDataDTO) {

    }

    @Override
    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO) {
        Path path = Paths.get("../../index");
        SemanticFragment semanticFragment = luceneIndexDataDTO.getSemanticFragment();
        String chunk = semanticFragment.getText();
        try {
            Document document = new Document();
            document.add(
                    new LongPoint("userId",
                            luceneIndexDataDTO.getUserId())
            );

            document.add(
                    new StoredField("userId_store",
                            luceneIndexDataDTO.getUserId())
            );
            document.add(
                    new StringField(
                            "email",
                            luceneIndexDataDTO.getEmail(),
                            Field.Store.YES
                    )
            );

            document.add(
                    new Field(
                            "body",
                            chunk,
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
