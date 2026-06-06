package com.NeuroIndex.parser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

@Configuration
@EnableAsync
public class AsyncConfig {

    private final ExecutorService executorService;

    public AsyncConfig(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Bean(name = "keywordExtractionExecutor")
    public Executor keywordExtractionExecutor() {
        return executorService;
    }
}
