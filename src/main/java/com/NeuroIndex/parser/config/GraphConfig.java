package com.NeuroIndex.parser.config;

import com.NeuroIndex.parser.dtos.PhraseToNodeDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class GraphConfig {
    @Bean
    public ConcurrentHashMap<Long, HashSet<PhraseToNodeDTO>> directlyRelatedNodes() {
        return new ConcurrentHashMap<>();
    }
}
