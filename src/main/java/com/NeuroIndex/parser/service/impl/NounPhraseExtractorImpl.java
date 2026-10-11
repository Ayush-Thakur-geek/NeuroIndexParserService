package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.service.NounPhraseExtractor;
import lombok.extern.log4j.Log4j2;
import opennlp.tools.chunker.ChunkerME;
import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Log4j2
public class NounPhraseExtractorImpl implements NounPhraseExtractor {

    // ------------------------------------------------------------------
    // Limits — bound the cost of a pathological fragment
    // ------------------------------------------------------------------
    private static final int MAX_TEXT_CHARS          = 100_000;
    private static final int MAX_TOKENS_PER_SENTENCE = 512;
    private static final int MAX_PHRASE_WORDS        = 6;
    private static final int MIN_PHRASE_CHARS        = 2;

    // ------------------------------------------------------------------
    // Identifier detection by shape. Generalises to terms never seen before,
    // which is the part a hand-maintained dictionary can never do.
    // The leading lookahead requires at least one letter, so "2024" and
    // "3.11" are not promoted to proper nouns.
    // ------------------------------------------------------------------
    private static final Pattern CODE_TOKEN = Pattern.compile(
            "^(?=\\S*[A-Za-z])(?:"
                    + "[a-z]+(?:[A-Z][A-Za-z0-9]*)+"          // camelCase
                    + "|[A-Z][a-z0-9]*(?:[A-Z][A-Za-z0-9]*)+" // PascalCase
                    + "|\\w+(?:_\\w+)+"                       // snake_case
                    + "|\\w+(?:\\.\\w+)+"                     // dotted.path
                    + "|[A-Za-z]+\\d+[A-Za-z0-9]*"            // bm25, oauth2, k8s
                    + ")$");

    /** Dotted-path shapes that are prose, not identifiers. */
    private static final Set<String> NON_IDENTIFIERS = Set.of("e.g", "i.e", "etc", "vs", "a.k.a");

    // ------------------------------------------------------------------
    // Phrase edge trimming. OpenNLP NP chunks legitimately include
    // determiners and pronouns; they are noise at the edges of a stored
    // phrase but meaningful inside it ("index out of bounds").
    // ------------------------------------------------------------------
    private static final Set<String> EDGE_STOPWORDS = Set.of(
            "a","an","the","this","that","these","those",
            "my","your","his","her","its","our","their",
            "i","you","he","she","it","we","they",
            "some","any","all","each","every","another","other","such","same",
            "no","none","both","either","neither","much","many","more","most","few","several"
    );

    // ------------------------------------------------------------------
    // Tag overrides — DISAMBIGUATION ONLY.
    //
    // Deliberately excluded: index, filter, record, cache, map, stream,
    // query, set, rest, service, document, object, field, term, request,
    // handler, chunk, work, check, use. Every one of those is also a common
    // verb, and forcing a noun tag makes the chunker emit phrases that do
    // not exist ("index the document" -> NP "index").
    // ------------------------------------------------------------------
    private static final Map<String, String> TECHNICAL_TERM_TAGS = buildTechnicalTermTags();

    private static Map<String, String> buildTechnicalTermTags() {
        Map<String, String> m = new HashMap<>();

        // Unambiguous proper nouns the model mis-tags as common nouns.
        String[] properNouns = {
                "java","jvm","jdk","jre","spring","springboot","springai","hibernate","jpa",
                "lucene","opennlp","solr","elasticsearch",
                "postgres","postgresql","mysql","mongodb","redis","sqlite","pgvector","kafka","rabbitmq",
                "websocket","http","https","tcp","udp","grpc","graphql","nginx",
                "jwt","oauth","oauth2","tls","ssl",
                "bm25","idf","tfidf","hnsw","ivf","faiss",
                "llm","chatgpt","claude","gemini","ollama","nomic","bgem3","openai","anthropic",
                "docker","kubernetes","linux","ubuntu","git","github","maven","gradle",
                "json","yaml","xml","sql","nosql","api","sdk","dto","crud","uuid",
                "completablefuture","concurrenthashmap","hashmap","arraylist","hashset","linkedlist",
                "directoryreader","indexwriter","indexreader","storedfield","longpoint","termsenum"
        };
        for (String w : properNouns) put(m, w, "NNP");

        // Domain nouns with no common verb sense.
        String[] commonNouns = {
                "centroid","embedding","embeddings","tokenizer","analyzer","chunker","postagger",
                "middleware","payload","endpoint","servlet","interceptor","annotation",
                "authentication","authorization","encryption","credential","certificate",
                "similarity","retrieval","corpus","keyword","keywords","latency","throughput",
                "concurrency","deserialization","serialization","idempotency"
        };
        for (String w : commonNouns) put(m, w, "NN");

        return Map.copyOf(m);
    }

    private static void put(Map<String, String> m, String word, String tag) {
        String previous = m.put(word, tag);
        if (previous != null && !previous.equals(tag)) {
            log.warn("Duplicate tag override for [{}]: {} replaced by {}", word, previous, tag);
        }
    }

    // ------------------------------------------------------------------
    // Models are immutable and shareable; the *ME wrappers are NOT
    // thread-safe (they hold mutable beam-search state), so each thread
    // gets its own instance.
    // ------------------------------------------------------------------
    private final ThreadLocal<TokenizerME>        tokenizer;
    private final ThreadLocal<POSTaggerME>        posTagger;
    private final ThreadLocal<ChunkerME>          chunker;
    private final ThreadLocal<SentenceDetectorME> sentenceDetector;   // null when model absent

    public NounPhraseExtractorImpl() throws IOException {
        TokenizerModel tokenModel;
        POSModel       posModel;
        ChunkerModel   chunkerModel;

        try (InputStream tokenIn   = getClass().getResourceAsStream("/models/en-token.bin");
             InputStream posIn     = getClass().getResourceAsStream("/models/en-pos-maxent.bin");
             InputStream chunkerIn = getClass().getResourceAsStream("/models/en-chunker.bin")) {

            requireResource(tokenIn,   "/models/en-token.bin");
            requireResource(posIn,     "/models/en-pos-maxent.bin");
            requireResource(chunkerIn, "/models/en-chunker.bin");

            tokenModel   = new TokenizerModel(tokenIn);
            posModel     = new POSModel(posIn);
            chunkerModel = new ChunkerModel(chunkerIn);
        }

        // Optional: POS models are trained per-sentence, so splitting first
        // materially improves tagging. Degrade gracefully if absent.
        SentenceModel loadedSentenceModel = null;
        try (InputStream sentIn = getClass().getResourceAsStream("/models/en-sent.bin")) {
            if (sentIn != null) {
                loadedSentenceModel = new SentenceModel(sentIn);
            } else {
                log.warn("/models/en-sent.bin not found — tagging whole fragments as one sentence, "
                        + "which reduces POS accuracy. Add the model to improve phrase quality.");
            }
        }

        this.tokenizer = ThreadLocal.withInitial(() -> new TokenizerME(tokenModel));
        this.posTagger = ThreadLocal.withInitial(() -> new POSTaggerME(posModel));
        this.chunker   = ThreadLocal.withInitial(() -> new ChunkerME(chunkerModel));

        final SentenceModel sentenceModel = loadedSentenceModel;
        this.sentenceDetector = sentenceModel == null
                ? null
                : ThreadLocal.withInitial(() -> new SentenceDetectorME(sentenceModel));
    }

    private void requireResource(InputStream stream, String resourcePath) throws IOException {
        if (stream == null) {
            throw new IOException("Required model resource not found on classpath: " + resourcePath);
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    @Override
    public List<String> extractNounPhrase(String text) {
        if (text == null || text.isBlank()) return List.of();

        String input = text.length() > MAX_TEXT_CHARS
                ? text.substring(0, MAX_TEXT_CHARS)
                : text;

        // Key = lowercase form (dedup), value = first-seen surface form.
        Map<String, String> phrases = new LinkedHashMap<>();

        for (String sentence : splitSentences(input)) {
            if (sentence.isBlank()) continue;
            try {
                collectFromSentence(sentence, phrases);
            } catch (RuntimeException e) {
                // One bad sentence must not lose the rest of the fragment.
                log.warn("Phrase extraction failed for sentence of {} chars: {}",
                        sentence.length(), e.toString());
            }
        }

        return new ArrayList<>(phrases.values());
    }

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    private String[] splitSentences(String text) {
        if (sentenceDetector == null) return new String[] { text };
        return sentenceDetector.get().sentDetect(text);
    }

    private void collectFromSentence(String sentence, Map<String, String> out) {
        String[] tokens = tokenizer.get().tokenize(sentence);
        if (tokens.length == 0) return;

        if (tokens.length > MAX_TOKENS_PER_SENTENCE) {
            tokens = Arrays.copyOf(tokens, MAX_TOKENS_PER_SENTENCE);
        }

        String[] posTags = posTagger.get().tag(tokens);
        applyTagOverrides(tokens, posTags);

        String[] chunks = chunker.get().chunk(tokens, posTags);
        int limit = Math.min(chunks.length, tokens.length);   // defensive

        StringBuilder current = new StringBuilder();

        for (int i = 0; i < limit; i++) {
            String chunkTag = chunks[i];

            if ("B-NP".equals(chunkTag)) {
                flush(current, out);
                current.append(tokens[i]);
            } else if ("I-NP".equals(chunkTag)) {
                if (current.isEmpty()) {
                    current.append(tokens[i]);            // I-NP without B-NP
                } else {
                    current.append(' ').append(tokens[i]);
                }
            } else {
                flush(current, out);
            }
        }
        flush(current, out);
    }

    private void flush(StringBuilder current, Map<String, String> out) {
        if (current.isEmpty()) return;
        addCandidate(current.toString(), out);
        current.setLength(0);
    }

    /**
     * Dictionary first (most specific), then shape detection. A token the
     * dictionary does not know but that looks like an identifier is treated
     * as a proper noun so the chunker keeps it inside the noun phrase.
     */
    private void applyTagOverrides(String[] tokens, String[] posTags) {
        for (int i = 0; i < tokens.length && i < posTags.length; i++) {
            String token = tokens[i];
            String lower = token.toLowerCase(Locale.ROOT);

            String dictionaryTag = TECHNICAL_TERM_TAGS.get(lower);
            if (dictionaryTag != null) {
                posTags[i] = dictionaryTag;
                continue;
            }

            if (token.length() >= 3
                    && !NON_IDENTIFIERS.contains(lower)
                    && CODE_TOKEN.matcher(token).matches()) {
                posTags[i] = "NNP";
            }
        }
    }

    // ------------------------------------------------------------------
    // Phrase cleanup
    // ------------------------------------------------------------------

    private void addCandidate(String rawPhrase, Map<String, String> out) {
        String phrase = trimPhraseEdges(rawPhrase);
        if (phrase.isEmpty()) return;

        String[] words = phrase.split("\\s+");
        if (words.length > MAX_PHRASE_WORDS)            return;
        if (phrase.length() < MIN_PHRASE_CHARS)         return;
        if (phrase.chars().noneMatch(Character::isLetterOrDigit)) return;

        out.putIfAbsent(phrase.toLowerCase(Locale.ROOT), phrase);
    }

    /**
     * Strips determiners and pronouns from both ends. Interior function
     * words are preserved — "index out of bounds" must stay intact.
     */
    private String trimPhraseEdges(String phrase) {
        String trimmed = phrase.trim();
        if (trimmed.isEmpty()) return "";

        Deque<String> words = new ArrayDeque<>(Arrays.asList(trimmed.split("\\s+")));

        while (!words.isEmpty() && isEdgeNoise(words.peekFirst())) words.pollFirst();
        while (!words.isEmpty() && isEdgeNoise(words.peekLast()))  words.pollLast();

        return String.join(" ", words);
    }

    private boolean isEdgeNoise(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (EDGE_STOPWORDS.contains(lower)) return true;
        return word.chars().noneMatch(Character::isLetterOrDigit);   // stray punctuation token
    }
}
