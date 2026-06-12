package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.NounPhraseExtractionDTO;
import com.NeuroIndex.parser.service.NounPhraseExtractor;
import lombok.extern.log4j.Log4j2;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@Log4j2
public class NounPhraseExtractorImpl implements NounPhraseExtractor {
    private final Tokenizer tokenizer;
    private final POSTaggerME posTagger;
    private final ChunkerME chunker;

    public NounPhraseExtractorImpl() throws IOException {

        try (
                InputStream tokenModelIn =
                        getClass().getResourceAsStream("/models/en-token.bin");

                InputStream posModelIn =
                        getClass().getResourceAsStream("/models/en-pos-maxent.bin");

                InputStream chunkerModelIn =
                        getClass().getResourceAsStream("/models/en-chunker.bin")
        ) {

            System.out.println(tokenModelIn);

            tokenizer =
                    new TokenizerME(
                            new TokenizerModel(tokenModelIn)
                    );

            posTagger =
                    new POSTaggerME(
                            new POSModel(posModelIn)
                    );

            chunker =
                    new ChunkerME(
                            new ChunkerModel(chunkerModelIn)
                    );
        }
    }


    @Override
    public List<String> extractNounPhrase(String text) {
        String[] tokens = tokenizer.tokenize(text);

        String[] posTags = posTagger.tag(tokens);

        String[] chunks = chunker.chunk(tokens, posTags);
        List<String> nounPhrases =
                new ArrayList<>();

        StringBuilder currentPhrase =
                new StringBuilder();

        for (int i = 0; i < chunks.length; i++) {

            String chunkTag =
                    chunks[i];

            if ("B-NP".equals(chunkTag)) {

                if (!currentPhrase.isEmpty()) {

                    nounPhrases.add(
                            currentPhrase.toString().trim()
                    );

                    currentPhrase.setLength(0);
                }

                currentPhrase.append(tokens[i]);

            } else if ("I-NP".equals(chunkTag)) {

                currentPhrase
                        .append(" ")
                        .append(tokens[i]);

            } else {

                if (!currentPhrase.isEmpty()) {

                    nounPhrases.add(
                            currentPhrase.toString().trim()
                    );

                    currentPhrase.setLength(0);
                }
            }
        }

        if (!currentPhrase.isEmpty()) {

            nounPhrases.add(
                    currentPhrase.toString().trim()
            );
        }

        return nounPhrases;
    }
}
