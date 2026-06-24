package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.dtos.LuceneKeywordExtractDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.helperClasses.KeywordCandidate;
import com.NeuroIndex.parser.helperClasses.KeywordEmbeddingAccumulator;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@Log4j2
public class KeywordExtractionServiceImpl implements KeyWordExtractionService {

    private final IndexWriter indexWriter;
    private final ExecutorService executorService;
    private static final FieldType BODY_FIELD_TYPE;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, KeywordEmbeddingAccumulator>> keywordAccumulator;
//    private final ConcurrentHashMap<String, float[]> keywordToCentroid;
    private final ConcurrentHashMap<Long, List<String>> fragmentIdToNounPhrase;
    private final UnifiedJedis jedis;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Integer>> phraseOccurrenceByUser;

    // Tunable constants
    private static final float THRESHOLD_FILTER_FOR_KEYWORDS;
    private static final int    MIN_KEYWORD_LENGTH          = 3;
    private static final int    MAX_KEYWORD_LENGTH          = 50;
    private static final int    MIN_DOC_FREQUENCY           = 2;   // ignore hapax legomena
    private static final double SCORE_PERCENTILE_THRESHOLD  = 0.60; // keep top 40 % by score mass
    private static final double BM25_K1                     = 1.5;
    private static final double BM25_B                      = 0.75;
    private static final Set<String> STOPWORDS              = Set.of(
            "the","a","an","and","or","but","in","on","at","to","for",
            "of","with","by","from","is","was","are","were","be","been",
            "has","have","had","it","its","this","that","these","those",
            "as","not","no","so","if","do","did","can","will","would",
            "could","should","may","might","shall","also","just","into",
            "about","than","then","when","where","which","who","whom",
            "what","how","all","each","any","both","more","most","other",
            "such","only","own","same","too","very","up","out","over",
            "i","you","he","she","we","they","me","him","her","us","them"
    );

    static {
        BODY_FIELD_TYPE = new FieldType();
        BODY_FIELD_TYPE.setStored(true);
        BODY_FIELD_TYPE.setTokenized(true);
        BODY_FIELD_TYPE.setStoreTermVectors(true);
        BODY_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        BODY_FIELD_TYPE.freeze();

        THRESHOLD_FILTER_FOR_KEYWORDS = 1.0f; // now actually enforced
    }

    KeywordExtractionServiceImpl(
            IndexWriter indexWriter,
            ExecutorService executorService,
            ConcurrentHashMap<Long, ConcurrentHashMap<String, KeywordEmbeddingAccumulator>> keywordAccumulator,
            ConcurrentHashMap<Long, List<String>> fragmentIdToNounPhrase,
            ObjectMapper objectMapper,
            UnifiedJedis jedis,
            ConcurrentHashMap<Long, ConcurrentHashMap<String, Integer>> phraseOccurrenceByUser
    ) {
        this.indexWriter        = indexWriter;
        this.executorService    = executorService;
        this.keywordAccumulator = keywordAccumulator;
        this.fragmentIdToNounPhrase = fragmentIdToNounPhrase;
        this.objectMapper = objectMapper;
        this.jedis = jedis;
        this.phraseOccurrenceByUser = phraseOccurrenceByUser;
    }

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

    @Override
    public void extractingKeyWords(List<LuceneKeywordExtractDTO> batch) {

        if (batch == null || batch.isEmpty()) return;

        try (DirectoryReader directoryReader = DirectoryReader.open(indexWriter)) {

            Map<Long, Integer> fragmentToDocIdMap = buildFragmentDocIdMap(directoryReader);

            int   totalDocs    = directoryReader.numDocs();
            float avgDocLength = computeAverageDocLength(directoryReader);

            // Group touched keywords per user, so computeCentroids only
            // pushes the fields that actually changed for that user.
            Map<Long, Set<String>> touchedKeywordsByUser = new HashMap<>();

            for (LuceneKeywordExtractDTO dto : batch) {

                Integer docId = fragmentToDocIdMap.get(dto.getSemanticFragmentId());
                if (docId == null) continue;

                Long userId = dto.getUserId();

                List<String> nounPhrases = fragmentIdToNounPhrase.getOrDefault(
                        dto.getSemanticFragmentId(),
                        Collections.emptyList()
                );

                Terms terms = directoryReader.termVectors().get(docId, "body");
                if (terms == null) continue;

                int docLength = computeDocLength(directoryReader, docId);

                List<KeywordCandidate> candidates =
                        scoreCandidates(directoryReader, terms, totalDocs, docLength, avgDocLength);

                if (candidates.isEmpty()) continue;

                List<KeywordCandidate> selected = selectByScoreDistribution(candidates);

                Set<String> keywordsThisFragment = accumulateEmbeddings(userId, selected, dto.getEmbeddings());

                touchedKeywordsByUser
                        .computeIfAbsent(userId, id -> new HashSet<>())
                        .addAll(keywordsThisFragment);

                log.info("selected keywords: {}", selected);

                Set<String> selectedKeywords = selected.stream()
                        .map(KeywordCandidate::keyword)
                        .map(this::normalizeWords)
                        .collect(Collectors.toSet());

                List<String> validPhrases = filterPhrasesByKeywordOverlap(nounPhrases, selectedKeywords);

                // Phrases are recorded as graph-node candidates only.
                // No vector is computed or stored for them here.
                recordPhraseOccurrences(userId, validPhrases);

                log.info("validPhrases: {} for text: {}", validPhrases, dto.getText());
            }

            // Push only the touched keywords per user to Redis.
            for (Map.Entry<Long, Set<String>> entry : touchedKeywordsByUser.entrySet()) {
                computeCentroids(entry.getKey(), entry.getValue());
            }

        } catch (Exception e) {
            log.error("Error extracting keywords", e);
            throw new CustomException(e.getMessage(), "KEYWORD_EXTRACTION_ERROR", 500, e);
        }
    }

    // Per-user record of which phrases exist as graph-node candidates,
    // and how often each was observed. No vector data — vectors are
    // derived on demand from keyword centroids when something needs them.

    private void recordPhraseOccurrences(Long userId, List<String> validPhrases) {

        ConcurrentHashMap<String, Integer> userPhrases =
                phraseOccurrenceByUser.computeIfAbsent(userId, id -> new ConcurrentHashMap<>());

        for (String phrase : validPhrases) {
            String normalized = normalizePhrase(phrase);
            if (normalized.isEmpty()) continue;
            userPhrases.merge(normalized, 1, Integer::sum);
        }
    }

    /**
     * Keeps a noun phrase if at least one of its constituent words matches a
     * BM25-selected keyword (after normalization).
     *
     * NOTE: a single-word overlap threshold is intentionally permissive —
     * revisit if you want to require majority overlap for multi-word phrases.
     */
    private List<String> filterPhrasesByKeywordOverlap(
            List<String> nounPhrases,
            Set<String>  selectedKeywords
    ) {
        if (nounPhrases.isEmpty() || selectedKeywords.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> validPhrases = new ArrayList<>();

        for (String phrase : nounPhrases) {
            if (phrase == null || phrase.isBlank()) continue;

            boolean hasOverlap = Arrays.stream(phrase.trim().split("\\s+"))
                    .map(this::normalizeWords)
                    .filter(w -> !w.isEmpty())
                    .anyMatch(selectedKeywords::contains);

            if (hasOverlap) {
                validPhrases.add(phrase);
            }
        }

        return validPhrases;
    }

    private String normalizeWords(String word) {
        if (word == null) return "";
        return word.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", ""); // strip underscores, punctuation, etc.
    }

// ---------------------------------------------------------------------------
// Step 1 – index scan (unchanged shape, extracted for clarity)
// ---------------------------------------------------------------------------

    private Map<Long, Integer> buildFragmentDocIdMap(DirectoryReader reader) throws IOException {
        Map<Long, Integer> map = new HashMap<>(reader.maxDoc());
        for (int docId = 0; docId < reader.maxDoc(); docId++) {
            Document doc = reader.storedFields().document(docId);
            String raw   = doc.get("semanticFragmentId_store");
            if (raw != null) {
                map.put(Long.parseLong(raw), docId);
            }
        }
        return map;
    }

// ---------------------------------------------------------------------------
// Step 2a – corpus average document length for BM25
// ---------------------------------------------------------------------------

    private float computeAverageDocLength(DirectoryReader reader) throws IOException {
        long totalTokens = 0;
        int  docCount    = 0;
        for (int docId = 0; docId < reader.maxDoc(); docId++) {
            Terms terms = reader.termVectors().get(docId, "body");
            if (terms == null) continue;
            totalTokens += terms.getSumTotalTermFreq();
            docCount++;
        }
        return docCount == 0 ? 1.0f : (float) totalTokens / docCount;
    }

// ---------------------------------------------------------------------------
// Step 2b – per-document token count
// ---------------------------------------------------------------------------

    private int computeDocLength(DirectoryReader reader, int docId) throws IOException {
        Terms terms = reader.termVectors().get(docId, "body");
        if (terms == null) return 1;
        return (int) terms.getSumTotalTermFreq();
    }

// ---------------------------------------------------------------------------
// Step 3 – score all terms in one document using BM25
// ---------------------------------------------------------------------------

    private List<KeywordCandidate> scoreCandidates(
            DirectoryReader reader,
            Terms           terms,
            int             totalDocs,
            int             docLength,
            float           avgDocLength
    ) throws IOException {

        TermsEnum termsEnum = terms.iterator();
        List<KeywordCandidate> candidates = new ArrayList<>();
        BytesRef termRef;

        while ((termRef = termsEnum.next()) != null) {

            String keyword = termRef.utf8ToString().toLowerCase();

            // --- Noise filters -------------------------------------------------
            if (!isValidKeyword(keyword)) continue;

            int df = reader.docFreq(new Term("body", keyword));

            // Ignore very rare terms (likely noise / OCR errors)
            if (df < MIN_DOC_FREQUENCY) continue;

            long rawTf = termsEnum.totalTermFreq();

            // --- BM25 score ----------------------------------------------------
            double bm25Score = computeBM25(rawTf, df, totalDocs, docLength, avgDocLength);

            if (bm25Score < THRESHOLD_FILTER_FOR_KEYWORDS) continue;

            candidates.add(new KeywordCandidate(keyword, bm25Score));
        }

        return candidates;
    }

// ---------------------------------------------------------------------------
// BM25 scoring (self-consistent, length-normalised)
// ---------------------------------------------------------------------------

    private double computeBM25(
            long  tf,
            int   df,
            int   totalDocs,
            int   docLength,
            float avgDocLength
    ) {
        // Standard Robertson/Sparck-Jones IDF (always ≥ 0)
        double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));

        // Length-normalised TF
        double normTf = (tf * (BM25_K1 + 1.0))
                / (tf + BM25_K1 * (1.0 - BM25_B + BM25_B * docLength / avgDocLength));

        return idf * normTf;
    }

// ---------------------------------------------------------------------------
// Token validity guards
// ---------------------------------------------------------------------------

    private boolean isValidKeyword(String token) {
        if (token == null) return false;
        int len = token.length();
        if (len < MIN_KEYWORD_LENGTH || len > MAX_KEYWORD_LENGTH) return false;
        if (STOPWORDS.contains(token.toLowerCase(Locale.ROOT)))   return false;
        if (!token.chars().anyMatch(Character::isLetter))         return false; // purely numeric / punctuation
        if (token.chars().filter(c -> !Character.isLetterOrDigit(c) && c != '-' && c != '_').count() > 2)
            return false; // too many special chars → noise
        return true;
    }

// ---------------------------------------------------------------------------
// Step 4 – score-distribution-based selection (replaces hard top-K)
// ---------------------------------------------------------------------------

    /**
     * Keeps candidates whose cumulative score mass is within the top
     * {@code SCORE_PERCENTILE_THRESHOLD} fraction, AND whose individual score
     * is above the mean.  This is adaptive: a document with 2 strong keywords
     * keeps 2; one with 20 evenly-scored terms keeps ~8–12.
     */
    private List<KeywordCandidate> selectByScoreDistribution(List<KeywordCandidate> candidates) {

        if (candidates.isEmpty()) return candidates;

        // Sort descending
        candidates.sort(Comparator.comparingDouble(KeywordCandidate::score).reversed());

        double totalScore = candidates.stream().mapToDouble(KeywordCandidate::score).sum();
        double meanScore  = totalScore / candidates.size();
        double budget     = totalScore * SCORE_PERCENTILE_THRESHOLD;

        List<KeywordCandidate> selected = new ArrayList<>();
        double accumulated = 0.0;

        for (KeywordCandidate c : candidates) {
            if (accumulated >= budget) break;       // score-mass budget exhausted
            if (c.score() < meanScore * 0.5) break; // sharp drop-off guard
            selected.add(c);
            accumulated += c.score();
        }

        return selected;
    }

// ---------------------------------------------------------------------------
// Step 5 – thread-safe embedding accumulation (fixed race condition)
// ---------------------------------------------------------------------------

    private Set<String> accumulateEmbeddings(Long userId, List<KeywordCandidate> keywords, float[] embedding) {

        ConcurrentHashMap<String, KeywordEmbeddingAccumulator> userAccumulator =
                keywordAccumulator.computeIfAbsent(userId, id -> new ConcurrentHashMap<>());

        Set<String> touched = new HashSet<>();

        for (KeywordCandidate candidate : keywords) {
            String keyword = candidate.keyword();

            KeywordEmbeddingAccumulator acc = userAccumulator.computeIfAbsent(
                    keyword,
                    k -> new KeywordEmbeddingAccumulator(new float[embedding.length], 0)
            );

            synchronized (acc) {
                float[] sum = acc.getEmbeddingSum();
                for (int j = 0; j < embedding.length; j++) {
                    sum[j] += embedding[j];
                }
                acc.incrementCount();
            }

            touched.add(keyword);
        }

        return touched;
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
            fragmentIdToNounPhrase.put(luceneIndexDataDTO.getSemanticFragmentId(), luceneIndexDataDTO.getNounPhrases());
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

    private void computeCentroids(Long userId, Set<String> touchedKeywords) {

        String redisKey = "centroids:user:" + userId;

        ConcurrentHashMap<String, KeywordEmbeddingAccumulator> userAccumulator =
                keywordAccumulator.get(userId);

        if (userAccumulator == null) return;

        for (String keyword : touchedKeywords) {

            KeywordEmbeddingAccumulator acc = userAccumulator.get(keyword);
            if (acc == null) continue;

            long count = acc.getOccurrenceCount();
            if (count == 0) continue;

            float[] sum = acc.getEmbeddingSum();
            float[] centroid = new float[sum.length];

            synchronized (acc) {
                for (int i = 0; i < sum.length; i++) {
                    centroid[i] = sum[i] / count;
                }
            }

            try {
                String centroidJson = objectMapper.writeValueAsString(centroid);
                jedis.hset(redisKey, keyword, centroidJson);
            } catch (JsonProcessingException e) {
                log.error("Error while converting centroid for keyword [{}]: {}", keyword, e.getMessage());
            }
        }
    }

    /**
     * Computes a phrase's vector on demand from its constituent words'
     * centroids. Not cached — call this only when a consumer (graph
     * builder, similarity check) actually needs the vector, since each
     * call costs one Redis read per distinct word in the phrase.
     */
    public float[] getPhraseCentroid(Long userId, String phrase) throws JsonProcessingException {

        String redisKey = "centroids:user:" + userId;
        String[] words = normalizePhrase(phrase).split(" ");

        List<float[]> wordCentroids = new ArrayList<>();

        for (String word : words) {
            String json = jedis.hget(redisKey, word);
            if (json == null) continue;
            wordCentroids.add(objectMapper.readValue(json, float[].class));
        }

        if (wordCentroids.isEmpty()) return null; // no centroid evidence yet for any word

        return averageVectors(wordCentroids);
    }

    /**
     * Normalizes a phrase by applying the same per-word normalization used
     * for keywords (lowercase, strip non-alphanumeric chars), then rejoining
     * with single spaces. Reuses normalizeWords so keyword and phrase
     * normalization can never drift out of sync.
     *
     * Blank/empty words (e.g. from punctuation-only tokens) are dropped
     * rather than left as empty strings, so "WebSocket - handler" doesn't
     * normalize to "websocket  handler" with a double space.
     */
    private String normalizePhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return "";

        return Arrays.stream(phrase.trim().split("\\s+"))
                .map(this::normalizeWords)
                .filter(w -> !w.isEmpty())
                .collect(Collectors.joining(" "));
    }

    /**
     * Averages a list of equal-length vectors element-wise.
     *
     * Assumes all vectors share the same dimensionality (true here since
     * every centroid comes from the same embedding model). Throws if the
     * list is empty — callers (e.g. getPhraseCentroid) must check for that
     * before calling, since "average of nothing" has no sensible vector
     * result and silently returning a zero-vector would be misleading
     * (indistinguishable from a real centroid that happens to sum near zero).
     */
    private float[] averageVectors(List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("Cannot average an empty list of vectors");
        }

        int dim = vectors.getFirst().length;
        float[] sum = new float[dim];

        for (float[] v : vectors) {
            if (v.length != dim) {
                throw new IllegalArgumentException(
                        "Vector dimension mismatch: expected " + dim + " but got " + v.length
                );
            }
            for (int i = 0; i < dim; i++) {
                sum[i] += v[i];
            }
        }

        for (int i = 0; i < dim; i++) {
            sum[i] /= vectors.size();
        }

        return sum;
    }
}
