package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.dtos.LuceneKeywordExtractDTO;
import com.NeuroIndex.parser.dtos.NounPhraseExtractionDTO;
import com.NeuroIndex.parser.exception.CustomException;
import com.NeuroIndex.parser.helperClasses.KeywordCandidate;
import com.NeuroIndex.parser.helperClasses.KeywordEmbeddingAccumulator;
import com.NeuroIndex.parser.service.KeyWordExtractionService;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@Service
@Log4j2
public class KeywordExtractionServiceImpl implements KeyWordExtractionService {

    private final IndexWriter indexWriter;
    private final ExecutorService executorService;
    private static final FieldType BODY_FIELD_TYPE;
    private final ConcurrentHashMap<String, KeywordEmbeddingAccumulator> keywordAccumulator;
    private final ConcurrentHashMap<String, float[]> keywordToCentroid;
    private final ConcurrentHashMap<Document, String> documentToNounPhrase;

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
            ConcurrentHashMap<String, KeywordEmbeddingAccumulator> keywordAccumulator,
            ConcurrentHashMap<String, float[]> keywordToCentroid, ConcurrentHashMap<Document, String> documentToNounPhrase
    ) {
        this.indexWriter        = indexWriter;
        this.executorService    = executorService;
        this.keywordAccumulator = keywordAccumulator;
        this.keywordToCentroid  = keywordToCentroid;
        this.documentToNounPhrase = documentToNounPhrase;
    }

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

    @Override
    public void extractingKeyWords(List<LuceneKeywordExtractDTO> batch) {

        if (batch == null || batch.isEmpty()) return;

        try (DirectoryReader directoryReader = DirectoryReader.open(indexWriter)) {

            // --- 1. Build fragmentId → docId map once per batch ----------------
            Map<Long, Integer> fragmentToDocIdMap = buildFragmentDocIdMap(directoryReader);

            // --- 2. Pre-compute corpus-level stats needed for BM25 --------------
            int   totalDocs      = directoryReader.numDocs();
            float avgDocLength   = computeAverageDocLength(directoryReader);

            boolean anyAccumulated = false;

            for (LuceneKeywordExtractDTO dto : batch) {

                Integer docId = fragmentToDocIdMap.get(dto.getSemanticFragmentId());
                if (docId == null) continue;

                Terms terms = directoryReader.termVectors().get(docId, "body");
                if (terms == null) continue;

                int docLength = computeDocLength(directoryReader, docId);

                List<KeywordCandidate> candidates =
                        scoreCandidates(directoryReader, terms, totalDocs, docLength, avgDocLength);

                if (candidates.isEmpty()) continue;

                List<KeywordCandidate> selected = selectByScoreDistribution(candidates);

                accumulateEmbeddings(selected, dto.getEmbeddings());
                anyAccumulated = true;
                log.info("selected keywords: {}", selected);
            }

            // --- 3. Recompute centroids only if anything changed ----------------
            if (anyAccumulated) {
                computeCentroids();
            }


        } catch (Exception e) {
            log.error("Error extracting keywords", e);
            throw new CustomException(e.getMessage(), "KEYWORD_EXTRACTION_ERROR", 500, e);
        }
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

    private void accumulateEmbeddings(List<KeywordCandidate> keywords, float[] embedding) {

        for (KeywordCandidate candidate : keywords) {
            String keyword = candidate.keyword();

            // computeIfAbsent is atomic for the insertion; we then synchronize
            // on the *same* object reference for mutation — no double-lock gap.
            KeywordEmbeddingAccumulator acc = keywordAccumulator.computeIfAbsent(
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
        }
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

    private void computeCentroids() {

        keywordToCentroid.clear();

        for (
                Map.Entry<
                        String,
                        KeywordEmbeddingAccumulator
                        > entry
                : keywordAccumulator.entrySet()
        ) {

            KeywordEmbeddingAccumulator acc =
                    entry.getValue();

            long count =
                    acc.getOccurrenceCount();

            if (count == 0) {
                continue;
            }

            float[] sum =
                    acc.getEmbeddingSum();

            float[] centroid =
                    new float[sum.length];

            for (int i = 0; i < sum.length; i++) {

                centroid[i] =
                        sum[i] / count;
            }

            keywordToCentroid.put(
                    entry.getKey(),
                    centroid
            );
        }
    }
}
