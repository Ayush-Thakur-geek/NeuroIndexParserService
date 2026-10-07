package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.entity.models.SemanticFragment;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@Service
@RequiredArgsConstructor
@Log4j2
public class SearchServiceImpl implements SearchService {

    private final ExecutorService executor;
    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final SemanticFragmentRepo semanticFragmentRepo;

    @Override
    public List<SearchResponseDTO> fastSearch(String query, Long userId) throws ExecutionException, InterruptedException {

        List<LexicalHit> luceneSearchResults;

        Callable<List<LexicalHit>> luceneSearch = () ->
                searchLucene(userId, query, 50);

        Future<List<LexicalHit>> luceneFuture =
                executor.submit(luceneSearch);

        List<LexicalHit> luceneResults = luceneFuture.get();

        List<SearchResponseDTO> searchResponseDTOs = luceneResults.stream()
                .map(sr -> {
                    SearchResponseDTO searchResponseDTO = SearchResponseDTO.builder()
                            .userId(userId)
                            .fragmentId(sr.fragmentId)
                            .build();
                    return searchResponseDTO;
                }).toList();
        return searchResponseDTOs;
    }

    @Override
    public Message extractMessage(SearchResponseDTO searchResponseDTO, Long userId) {
        Long fragmentId = searchResponseDTO.getFragmentId();
        Optional<SemanticFragment> semanticFragment = semanticFragmentRepo.findById(fragmentId);
        Message message;
        if (semanticFragment.isPresent()) {
            SemanticFragment sf = semanticFragment.get();
            message = sf.getMessage();
        } else message = null;
        return message;
    }

    public List<LexicalHit> searchLucene(
            Long userId,
            String queryText,
            int limit
    ) throws IOException {

        if (userId == null) {
            throw new IllegalArgumentException("User ID is required");
        }

        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Result limit must be between 1 and 100"
            );
        }

        List<String> tokens = analyzeQuery(queryText);

        if (tokens.isEmpty()) {
            return List.of();
        }

        BooleanQuery.Builder textQuery = new BooleanQuery.Builder();

        for (String token : tokens) {
            Term term = new Term("body", token);

            Query exactQuery = new BoostQuery(
                    new TermQuery(term),
                    3.0f
            );

            int length = token.codePointCount(0, token.length());

            Query tokenQuery;

            if (length <= 2) {
                // Fuzzy matching very short tokens produces too much noise.
                tokenQuery = exactQuery;
            } else {
                // Starting heuristics; tune against real queries.
                int maxEdits = length >= 7 ? 2 : 1;

                Query fuzzyQuery = new FuzzyQuery(
                        term,
                        maxEdits,
                        0,      // No required prefix: handles first-letter typos.
                        50,     // Maximum term expansions.
                        true    // Treat adjacent transpositions as one edit.
                );

                /*
                 * Use the better match rather than adding exact and fuzzy
                 * scores together. FuzzyQuery also includes exact matches.
                 */
                tokenQuery = new DisjunctionMaxQuery(
                        List.of(exactQuery, fuzzyQuery),
                        0.0f
                );
            }

            textQuery.add(
                    tokenQuery,
                    BooleanClause.Occur.SHOULD
            );
        }

        // Initially allow a fragment matching any query token.
        textQuery.setMinimumNumberShouldMatch(1);

        BooleanQuery finalQuery = new BooleanQuery.Builder()
                .add(
                        textQuery.build(),
                        BooleanClause.Occur.MUST
                )
                .add(
                        LongPoint.newExactQuery("userId", userId),
                        BooleanClause.Occur.FILTER
                )
                .build();

        IndexSearcher searcher = searcherManager.acquire();

        try {
            TopDocs topDocs = searcher.search(finalQuery, limit);

            StoredFields storedFields =
                    searcher.getIndexReader().storedFields();

            List<LexicalHit> results = new ArrayList<>();

            for (ScoreDoc hit : topDocs.scoreDocs) {
                Document document = storedFields.document(
                        hit.doc,
                        Set.of("semanticFragmentId_store")
                );

                IndexableField fragmentIdField =
                        document.getField("semanticFragmentId_store");

                if (fragmentIdField == null
                        || fragmentIdField.numericValue() == null) {
                    throw new IllegalStateException(
                            "Missing stored fragment ID for Lucene docId="
                                    + hit.doc
                    );
                }

                results.add(new LexicalHit(
                        fragmentIdField.numericValue().longValue(),
                        hit.score
                ));
            }

            return results;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private List<String> analyzeQuery(
            String queryText
    ) throws IOException {

        Set<String> tokens = new LinkedHashSet<>();

        try (TokenStream stream =
                     analyzer.tokenStream("body", queryText)) {

            CharTermAttribute term =
                    stream.addAttribute(CharTermAttribute.class);

            stream.reset();

            while (stream.incrementToken()) {
                String token = term.toString();

                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }

            stream.end();
        }

        return new ArrayList<>(tokens);
    }

    public record LexicalHit(
            Long fragmentId,
            float score
    ) {}
}
