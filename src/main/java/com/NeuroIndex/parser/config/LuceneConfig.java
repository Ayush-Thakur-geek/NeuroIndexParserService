package com.NeuroIndex.parser.config;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class LuceneConfig {

    @Bean
    public Directory getDirectory() throws IOException {
        Path path = Paths.get("../../index");
        return FSDirectory.open(path);
    }

    @Bean
    public IndexWriterConfig getIndexWriterConfig() {
        return new IndexWriterConfig(new StandardAnalyzer());
    }

    @Bean
    public IndexWriter getIndexWriter() throws IOException {
        return new IndexWriter(getDirectory(), getIndexWriterConfig());
    }
}
