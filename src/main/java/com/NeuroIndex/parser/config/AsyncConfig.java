package com.NeuroIndex.parser.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "keywordExtractionExecutor")
    public Executor keywordExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("keyword-extraction-");
        executor.initialize();

        return executor;
    }

    @Bean(name = "graphFormationExecutor")
    public Executor graphFormationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("graph-formation-");
        executor.initialize();

        return executor;
    }

    @Bean(
            name = "searchGraphExecutor",
            destroyMethod = "shutdownNow"
    )
    public ExecutorService searchGraphExecutor() {

        AtomicInteger sequence = new AtomicInteger();

        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "search-graph-" + sequence.incrementAndGet()
            );

            thread.setDaemon(false);

            return thread;
        };

        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}