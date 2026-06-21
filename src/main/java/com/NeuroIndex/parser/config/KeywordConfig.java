package com.NeuroIndex.parser.config;

import com.NeuroIndex.parser.helperClasses.KeywordEmbeddingAccumulator;
import org.apache.lucene.document.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class KeywordConfig {

    @Bean
    public ConcurrentHashMap<
            String,
            KeywordEmbeddingAccumulator
            > keywordAccumulator() {

        return new ConcurrentHashMap<>();
    }

    @Bean
    public ConcurrentHashMap<String, float[]> keywordToCentroid() {

        return new ConcurrentHashMap<>();
    }

    @Bean
    public ConcurrentHashMap<Long, List<String>> fragmentIdToNounPhrase() {
        return new ConcurrentHashMap<>();
    }
}