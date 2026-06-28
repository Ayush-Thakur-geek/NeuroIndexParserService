package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.service.NounPhraseExtractor;
import lombok.extern.log4j.Log4j2;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.postag.POSDictionary;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerFactory;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.tokenize.Tokenizer;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Log4j2
public class NounPhraseExtractorImpl implements NounPhraseExtractor {
    private final Tokenizer tokenizer;
    private final POSTaggerME posTagger;
    private final ChunkerME chunker;

    private static final Map<String, String> TECHNICAL_TERM_TAGS = Map.ofEntries(
            // ===== Java =====
            Map.entry("java", "NNP"),
            Map.entry("jvm", "NNP"),
            Map.entry("jdk", "NNP"),
            Map.entry("jre", "NNP"),
            Map.entry("class", "NN"),
            Map.entry("interface", "NN"),
            Map.entry("object", "NN"),
            Map.entry("method", "NN"),
            Map.entry("constructor", "NN"),
            Map.entry("field", "NN"),
            Map.entry("property", "NN"),
            Map.entry("variable", "NN"),
            Map.entry("parameter", "NN"),
            Map.entry("argument", "NN"),
            Map.entry("package", "NN"),
            Map.entry("annotation", "NN"),
            Map.entry("enum", "NN"),
            Map.entry("record", "NN"),
            Map.entry("lambda", "NN"),
            Map.entry("stream", "NN"),
            Map.entry("optional", "NN"),
            Map.entry("thread", "NN"),
            Map.entry("executor", "NN"),
            Map.entry("future", "NN"),
            Map.entry("completablefuture", "NN"),
            Map.entry("callable", "NN"),
            Map.entry("runnable", "NN"),

            // ===== Spring =====
            Map.entry("spring", "NNP"),
            Map.entry("springboot", "NNP"),
            Map.entry("springai", "NNP"),
            Map.entry("bean", "NN"),
            Map.entry("autowired", "NN"),
            Map.entry("controller", "NN"),
            Map.entry("service", "NN"),
            Map.entry("repository", "NN"),
            Map.entry("entity", "NN"),
            Map.entry("component", "NN"),
            Map.entry("configuration", "NN"),
            Map.entry("transaction", "NN"),
            Map.entry("validator", "NN"),
            Map.entry("interceptor", "NN"),
            Map.entry("filter", "NN"),
            Map.entry("servlet", "NN"),
            Map.entry("dependency", "NN"),

            // ===== Networking =====
            Map.entry("websocket", "NNP"),
            Map.entry("endpoint", "NN"),
            Map.entry("socket", "NN"),
            Map.entry("request", "NN"),
            Map.entry("response", "NN"),
            Map.entry("payload", "NN"),
            Map.entry("handler", "NN"),
            Map.entry("middleware", "NN"),
            Map.entry("gateway", "NN"),
            Map.entry("proxy", "NN"),
            Map.entry("session", "NN"),
            Map.entry("cookie", "NN"),
            Map.entry("header", "NN"),
            Map.entry("http", "NNP"),
            Map.entry("https", "NNP"),
            Map.entry("tcp", "NNP"),
            Map.entry("udp", "NNP"),
            Map.entry("grpc", "NNP"),
            Map.entry("graphql", "NNP"),
            Map.entry("rest", "NNP"),

            // ===== Security =====
            Map.entry("jwt", "NNP"),
            Map.entry("oauth", "NNP"),
            Map.entry("oauth2", "NNP"),
            Map.entry("authentication", "NN"),
            Map.entry("authorization", "NN"),
            Map.entry("credential", "NN"),
            Map.entry("certificate", "NN"),
            Map.entry("hash", "NN"),
            Map.entry("encryption", "NN"),
            Map.entry("token", "NN"),

            // ===== Database =====
            Map.entry("database", "NN"),
            Map.entry("schema", "NN"),
            Map.entry("table", "NN"),
            Map.entry("column", "NN"),
            Map.entry("row", "NN"),
            Map.entry("query", "NN"),
            Map.entry("index", "NN"),
            Map.entry("constraint", "NN"),
            Map.entry("postgres", "NNP"),
            Map.entry("postgresql", "NNP"),
            Map.entry("mysql", "NNP"),
            Map.entry("mongodb", "NNP"),
            Map.entry("redis", "NNP"),
            Map.entry("sqlite", "NNP"),
            Map.entry("pgvector", "NNP"),

            // ===== Lucene =====
            Map.entry("lucene", "NNP"),
            Map.entry("analyzer", "NN"),
            Map.entry("tokenizer", "NN"),
            Map.entry("term", "NN"),
            Map.entry("termvector", "NN"),
            Map.entry("directoryreader", "NNP"),
            Map.entry("indexwriter", "NNP"),
            Map.entry("indexreader", "NNP"),
            Map.entry("document", "NN"),
            Map.entry("storedfield", "NNP"),
            Map.entry("longpoint", "NNP"),

            // ===== Retrieval =====
            Map.entry("bm25", "NNP"),
            Map.entry("embedding", "NN"),
            Map.entry("embeddings", "NN"),
            Map.entry("vector", "NN"),
            Map.entry("centroid", "NN"),
            Map.entry("similarity", "NN"),
            Map.entry("semantic", "JJ"),
            Map.entry("fragment", "NN"),
            Map.entry("fragments", "NN"),
            Map.entry("retrieval", "NN"),
            Map.entry("keyword", "NN"),
            Map.entry("keywords", "NN"),
            Map.entry("graph", "NN"),
            Map.entry("node", "NN"),
            Map.entry("edge", "NN"),
            Map.entry("cluster", "NN"),
            Map.entry("alias", "NN"),
            Map.entry("chunk", "NN"),
            Map.entry("chunking", "NN"),
            Map.entry("parser", "NN"),
            Map.entry("corpus", "NN"),
            Map.entry("documentfrequency", "NN"),
            Map.entry("idf", "NNP"),
            Map.entry("tf", "NNP"),

            // ===== OpenNLP =====
            Map.entry("opennlp", "NNP"),
            Map.entry("chunker", "NN"),
            Map.entry("postagger", "NN"),
            Map.entry("noun", "NN"),
            Map.entry("phrase", "NN"),
            Map.entry("tokenization", "NN"),
            Map.entry("pos", "NN"),

            // ===== AI =====
            Map.entry("ollama", "NNP"),
            Map.entry("nomic", "NNP"),
            Map.entry("bgem3", "NNP"),
            Map.entry("llm", "NNP"),
            Map.entry("chatgpt", "NNP"),
            Map.entry("claude", "NNP"),
            Map.entry("gemini", "NNP"),
            Map.entry("prompt", "NN"),
            Map.entry("completion", "NN"),
            Map.entry("inference", "NN"),
            Map.entry("transformer", "NN"),
            Map.entry("attention", "NN"),

            // ===== DevOps =====
            Map.entry("docker", "NNP"),
            Map.entry("container", "NN"),
            Map.entry("kubernetes", "NNP"),
            Map.entry("deployment", "NN"),
            Map.entry("pipeline", "NN"),

            // ===== Collections =====
            Map.entry("map", "NN"),
            Map.entry("hashmap", "NN"),
            Map.entry("concurrenthashmap", "NN"),
            Map.entry("list", "NN"),
            Map.entry("arraylist", "NN"),
            Map.entry("set", "NN"),
            Map.entry("hashset", "NN"),
            Map.entry("queue", "NN"),
            Map.entry("deque", "NN"),

            // ===== NeuroIndex =====
            Map.entry("userid", "NN"),
            Map.entry("conversationid", "NN"),
            Map.entry("messageid", "NN"),
            Map.entry("fragmentid", "NN"),
            Map.entry("roomid", "NN"),
            Map.entry("cache", "NN"),
            Map.entry("cachemanager", "NN"),
            Map.entry("userservice", "NN"),
            Map.entry("messageservice", "NN"),
            Map.entry("graphnode", "NN"),
            Map.entry("semanticunit", "NN"),
            Map.entry("semanticfragment", "NN"),
            Map.entry("keywordcandidate", "NN"),
            Map.entry("dto", "NN")
    );

    public NounPhraseExtractorImpl() throws IOException {

        try (
                InputStream tokenModelIn =
                        getClass().getResourceAsStream("/models/en-token.bin");

                InputStream posModelIn =
                        getClass().getResourceAsStream("/models/en-pos-maxent.bin");

                InputStream chunkerModelIn =
                        getClass().getResourceAsStream("/models/en-chunker.bin")
        ) {

            requireResource(tokenModelIn, "/models/en-token.bin");
            requireResource(posModelIn, "/models/en-pos-maxent.bin");
            requireResource(chunkerModelIn, "/models/en-chunker.bin");

            tokenizer = new TokenizerME(new TokenizerModel(tokenModelIn));
            posTagger = new POSTaggerME(new POSModel(posModelIn));
            chunker = new ChunkerME(new ChunkerModel(chunkerModelIn));
        }
    }

    private void requireResource(InputStream stream, String resourcePath) throws IOException {
        if (stream == null) {
            throw new IOException("Required model resource not found on classpath: " + resourcePath);
        }
    }


    @Override
    public List<String> extractNounPhrase(String text) {
        String[] tokens = tokenizer.tokenize(text);

        String[] posTags = posTagger.tag(tokens);

        // Override tags for known technical terms post-hoc, since the loaded
        // model's factory can't accept a tag dictionary after deserialization.
        applyTechnicalTermOverrides(tokens, posTags);

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

    private void applyTechnicalTermOverrides(String[] tokens, String[] posTags) {
        for (int i = 0; i < tokens.length; i++) {
            String normalized = tokens[i].toLowerCase(Locale.ROOT);
            String overrideTag = TECHNICAL_TERM_TAGS.get(normalized);
            if (overrideTag != null) {
                posTags[i] = overrideTag;
            }
        }
    }
}
