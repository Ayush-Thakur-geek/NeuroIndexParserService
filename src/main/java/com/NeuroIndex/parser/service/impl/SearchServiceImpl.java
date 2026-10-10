package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.*;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.repositories.SemanticEdgeRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentNodeRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.repositories.SemanticNodeRepo;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class SearchServiceImpl implements SearchService {

    private final ExecutorService executor;
    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private static final double TEMPERATURE = 0.5;
    private static final int TOP_RESULT_PERCENT = 40;
    private final SemanticFragmentNodeRepo semanticFragmentNodeRepo;
    private final SemanticNodeRepo semanticNodeRepo;
    private final SemanticEdgeRepo semanticEdgeRepo;
    private static final int DEPTH = 3;
    private static final long GRAPH_TIMEOUT_SECONDS = 5;
    private static final int MAX_GRAPH_NODES = 1_000;
    private static final int MAX_DIRECT_NEIGHBORS = 3;
    private final PlatformTransactionManager transactionManager;

    @Override
    public List<SearchResponseDTO> fastSearch(
            String query,
            Long userId
    ) throws ExecutionException, InterruptedException {

        long started = System.nanoTime();

        List<LexicalHit> luceneResults;

        try {
            luceneResults = searchLucene(userId, query, 50);
        } catch (IOException exception) {
            log.error(
                    "Lucene search failed for userId={}",
                    userId,
                    exception
            );

            throw new ExecutionException(exception);
        }

        if (luceneResults.isEmpty()) {
            log.debug("No Lucene results for userId={}", userId);
            return List.of();
        }

        // Lucene results already arrive in descending score order.
        Map<Long, LexicalHit> uniqueHits = new LinkedHashMap<>();

        for (LexicalHit hit : luceneResults) {
            uniqueHits.putIfAbsent(hit.fragmentId(), hit);
        }

        List<LexicalHit> rankedHits =
                new ArrayList<>(uniqueHits.values());

        int seedCount = rankedHits.size();

        if (seedCount > 20) {
            seedCount /= 2;
        }

        int deterministicCount =
                TOP_RESULT_PERCENT * seedCount / 100;

        int randomCount =
                seedCount - deterministicCount;

        List<LexicalHit> graphSeeds = new ArrayList<>(
                rankedHits.subList(0, deterministicCount)
        );

        List<LexicalHit> candidates = new ArrayList<>(
                rankedHits.subList(
                        deterministicCount,
                        rankedHits.size()
                )
        );

        graphSeeds.addAll(
                sampleWithoutReplacement(
                        candidates,
                        randomCount,
                        TEMPERATURE,
                        ThreadLocalRandom.current()
                )
        );

        graphSeeds.sort(
                Comparator.comparingDouble(LexicalHit::score)
                        .reversed()
        );

        // Pass an immutable snapshot to the worker.
        List<LexicalHit> seedSnapshot = List.copyOf(graphSeeds);

        log.info(
                "Starting graph search: userId={}, lexicalHits={}, seedFragments={}",
                userId,
                rankedHits.size(),
                seedSnapshot.size()
        );

        List<GraphHit> graphResults = List.of();
        Future<List<GraphHit>> graphFuture = null;

        try {
            long totalFragments = semanticFragmentRepo.count();
            graphFuture = executor.submit(
                    () -> graphTraversal(userId, seedSnapshot, totalFragments)
            );

            graphResults = graphFuture.get(
                    GRAPH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

        } catch (TimeoutException exception) {
            if (graphFuture != null) {
                graphFuture.cancel(true);
            }

            log.warn(
                    "Graph search timed out for userId={}; returning Lucene results",
                    userId
            );

        } catch (RejectedExecutionException exception) {
            log.warn(
                    "Graph executor rejected search for userId={}; "
                            + "returning Lucene results",
                    userId,
                    exception
            );

        } catch (ExecutionException exception) {
            log.error(
                    "Graph search failed for userId={}; returning Lucene results",
                    userId,
                    exception.getCause()
            );

        } catch (CancellationException exception) {
            log.warn(
                    "Graph search cancelled for userId={}; returning Lucene results",
                    userId
            );

        } catch (InterruptedException exception) {
            if (graphFuture != null) {
                graphFuture.cancel(true);
            }

            Thread.currentThread().interrupt();

            log.warn("Search interrupted for userId={}", userId);

            throw exception;
        }

        /*
         * Preserve the complete Lucene result list.
         * Sampling chooses graph entry points, not which lexical results survive.
         *
         * This is deduplication only—not final RRF/score fusion.
         */
        Set<Long> resultFragmentIds = new LinkedHashSet<>();

        for (LexicalHit hit : rankedHits) {
            resultFragmentIds.add(hit.fragmentId());
        }

        for (GraphHit hit : graphResults) {
            log.info("Graph search frag: {}", hit.fragmentId);
            resultFragmentIds.add(hit.fragmentId());
        }

        List<SearchResponseDTO> results =
                resultFragmentIds.stream()
                        .map(fragmentId -> SearchResponseDTO.builder()
                                .userId(userId)
                                .fragmentId(fragmentId)
                                .build())
                        .toList();

        long elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started
                );

        log.info(
                "Search completed: userId={}, lexicalHits={}, graphHits={}, "
                        + "uniqueResults={}, elapsedMs={}",
                userId,
                rankedHits.size(),
                graphResults.size(),
                results.size(),
                elapsedMillis
        );

        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public Message extractMessage(
            SearchResponseDTO searchResponseDTO,
            Long userId
    ) {
        SemanticFragment fragment =
                semanticFragmentRepo.findByIdAndUserId(
                        searchResponseDTO.getFragmentId(),
                        userId
                ).orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Search result not found"
                        )
                );

        return fragment.getMessage();
    }


    private List<GraphHit> graphTraversal(
            Long userId,
            List<LexicalHit> graphSeeds,
            long totalFragments
    ) {
        /*
         * The worker thread needs its own transaction.
         * A private @Transactional method would not establish one through
         * self-invocation.
         */
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.setReadOnly(true);

        List<GraphHit> hits = transaction.execute(status -> {
            checkGraphInterrupted();

            Set<Long> fragmentIds = graphSeeds.stream()
                    .map(LexicalHit::fragmentId)
                    .collect(Collectors.toSet());

            if (fragmentIds.isEmpty()) {
                return List.of();
            }

            List<SemanticFragmentNode> mappings =
                    semanticFragmentNodeRepo.findSeedMappings(
                            userId,
                            fragmentIds
                    );

            // The same node can belong to several seed fragments.
            Map<Long, SemanticNode> roots = new LinkedHashMap<>();

            for (SemanticFragmentNode mapping : mappings) {
                SemanticNode node = mapping.getNode();

                if (Objects.equals(node.getUserId(), userId)) {
                    roots.putIfAbsent(node.getId(), node);
                }
            }

            log.info(
                    "Graph seeds resolved: userId={}, fragmentMappings={}, nodes={}",
                    userId,
                    mappings.size(),
                    roots.size()
            );

            if (roots.isEmpty()) {
                return List.of();
            }

            return traverse(userId, roots.values(), totalFragments);
        });

        return hits == null ? List.of() : hits;
    }

    private List<GraphHit> traverse(
            Long userId,
            Collection<SemanticNode> roots,
            long totalFragments
    ) {
        Queue<SemanticNode> queue = new ArrayDeque<>();
        Set<Long> visited = new LinkedHashSet<>();

        for (SemanticNode root : roots) {
            if (visited.size() >= MAX_GRAPH_NODES) {
                break;
            }

            if (visited.add(root.getId())) {
                queue.add(root);
            }
        }

        boolean capped = visited.size() >= MAX_GRAPH_NODES;

        /*
         * DEPTH means number of edge hops.
         * Seed nodes are included even when DEPTH is zero.
         */
        for (int hop = 0;
             hop < DEPTH && !queue.isEmpty() && !capped;
             hop++) {

            checkGraphInterrupted();

            int levelSize = queue.size();

            log.debug(
                    "Graph traversal: userId={}, hop={}, frontier={}, visited={}",
                    userId,
                    hop + 1,
                    levelSize,
                    visited.size()
            );

            for (int i = 0; i < levelSize && !capped; i++) {
                checkGraphInterrupted();

                SemanticNode node = queue.remove();

                // Your existing method; scoring implementation remains unchanged.
                List<SemanticEdge> edges = Objects.requireNonNull(
                        getEdges(node, totalFragments),
                        "getEdges() must return a list"
                );

                for (SemanticEdge edge : edges) {
                    checkGraphInterrupted();

                    SemanticNode neighbor;

                    if (Objects.equals(
                            edge.getSourceNode().getId(),
                            node.getId()
                    )) {
                        neighbor = edge.getTargetNode();

                    } else if (Objects.equals(
                            edge.getTargetNode().getId(),
                            node.getId()
                    )) {
                        neighbor = edge.getSourceNode();

                    } else {
                        log.warn(
                                "Skipping non-incident edgeId={} for nodeId={}",
                                edge.getId(),
                                node.getId()
                        );
                        continue;
                    }

                    if (!Objects.equals(neighbor.getUserId(), userId)) {
                        log.warn(
                                "Skipping cross-user graph neighbor: "
                                        + "userId={}, nodeId={}",
                                userId,
                                neighbor.getId()
                        );
                        continue;
                    }

                    if (visited.add(neighbor.getId())) {
                        queue.add(neighbor);
                    }

                    if (visited.size() >= MAX_GRAPH_NODES) {
                        capped = true;
                        break;
                    }
                }
            }
        }

        if (capped) {
            log.warn(
                    "Graph traversal reached node cap: userId={}, cap={}",
                    userId,
                    MAX_GRAPH_NODES
            );
        }

        checkGraphInterrupted();

        /*
         * Get fragment IDs belonging to all reached nodes in one query.
         * Do not repeatedly return the original seed fragment.
         */
        List<SemanticFragmentNodeRepo.GraphPosting> postings =
                semanticFragmentNodeRepo.findGraphPostings(
                        userId,
                        visited
                );

        Map<Long, GraphHit> uniqueHits = new LinkedHashMap<>();

        for (SemanticFragmentNodeRepo.GraphPosting posting : postings) {
            uniqueHits.putIfAbsent(
                    posting.getFragmentId(),
                    new GraphHit(
                            posting.getFragmentId(),
                            posting.getMappingId()
                    )
            );
        }

        log.info(
                "Graph traversal completed: userId={}, visitedNodes={}, fragments={}",
                userId,
                visited.size(),
                uniqueHits.size()
        );

        return new ArrayList<>(uniqueHits.values());
    }

    private void checkGraphInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "Graph traversal interrupted"
            );
        }
    }

    private List<SemanticEdge> getEdges(
            SemanticNode node,
            long totalFragments
    ) {
        List<SemanticEdge> connectedEdges =
                semanticEdgeRepo.findAllConnectedToNode(node.getId());

        List<ScoredEdge> scoredEdges = new ArrayList<>();

        for (SemanticEdge edge : connectedEdges) {
            if (edge.getCooccurrenceCount() <= 0) {
                continue;
            }

            Double npmi = npmiCalculation(edge, totalFragments);

            if (npmi == null || npmi <= 0.0) {
                continue;
            }

            scoredEdges.add(new ScoredEdge(edge, npmi));
        }

        return scoredEdges.stream()
                .sorted(
                        Comparator.comparingDouble(ScoredEdge::npmi)
                                .reversed()
                                .thenComparing(scored -> scored.edge().getId())
                )
                .limit(MAX_DIRECT_NEIGHBORS)
                .map(ScoredEdge::edge)
                .toList();
    }

    private Double npmiCalculation(
            SemanticEdge edge,
            long totalFragments
    ) {
        if (totalFragments <= 0) {
            return null; // No population to calculate probabilities from.
        }

        Integer sourceCount =
                edge.getSourceNode().getFragmentCount();

        Integer targetCount =
                edge.getTargetNode().getFragmentCount();

        if (sourceCount == null || targetCount == null) {
            throw new IllegalStateException(
                    "Missing node fragment count for edgeId=" + edge.getId()
            );
        }

        long countA = sourceCount;
        long countB = targetCount;
        long countAB = edge.getCooccurrenceCount();

        /*
         * Validate that all counts describe the same fragment population.
         * Do not hide inflated Redis counts by clamping them.
         */
        if (countA <= 0
                || countB <= 0
                || countA > totalFragments
                || countB > totalFragments
                || countAB < 0
                || countAB > Math.min(countA, countB)
                || countAB < Math.max(
                0L,
                countA + countB - totalFragments
        )) {
            throw new IllegalStateException(
                    "Invalid NPMI counts for edgeId=" + edge.getId()
                            + ": N=" + totalFragments
                            + ", countA=" + countA
                            + ", countB=" + countB
                            + ", countAB=" + countAB
            );
        }

        if (countAB == 0) {
            // No observed direct association; score indirect evidence separately.
            return null;
        }

        if (countAB == totalFragments) {
            // P(A,B)=1 makes the normalization denominator zero.
            return null;
        }

        double pA = (double) countA / totalFragments;
        double pB = (double) countB / totalFragments;
        double pAB = (double) countAB / totalFragments;

        double pmi =
                Math.log(pAB) - Math.log(pA) - Math.log(pB);

        double npmi = pmi / -Math.log(pAB);

        // Only compensate for small floating-point rounding errors.
        return Math.max(-1.0, Math.min(1.0, npmi));
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

    private List<LexicalHit> sampleWithoutReplacement(
            List<LexicalHit> candidates,
            int count,
            double temperature,
            Random random
    ) {
        if (!Double.isFinite(temperature) || temperature <= 0) {
            throw new IllegalArgumentException(
                    "Temperature must be finite and greater than zero"
            );
        }

        if (count < 0 || count > candidates.size()) {
            throw new IllegalArgumentException(
                    "Invalid sample count"
            );
        }

        if (count == 0) {
            return List.of();
        }

        List<LexicalHit> pool = new ArrayList<>(candidates);
        List<LexicalHit> selected = new ArrayList<>(count);

        double minScore = pool.stream()
                .mapToDouble(LexicalHit::score)
                .min()
                .orElseThrow();

        double maxScore = pool.stream()
                .mapToDouble(LexicalHit::score)
                .max()
                .orElseThrow();

        double scoreRange = maxScore - minScore;

        while (selected.size() < count) {
            double[] weights = new double[pool.size()];
            double totalWeight = 0.0;

            for (int i = 0; i < pool.size(); i++) {
                /*
                 * Normalize scores to [0, 1], so temperature does not
                 * depend on the absolute scale of Lucene scores.
                 */
                double normalizedScore = scoreRange > 0
                        ? (pool.get(i).score() - minScore) / scoreRange
                        : 1.0;

                /*
                 * Subtracting 1 keeps the exponent non-positive.
                 * At temperature 0.5, even the lowest score has
                 * a positive weight.
                 */
                double weight = Math.exp(
                        (normalizedScore - 1.0) / temperature
                );

                weights[i] = weight;
                totalWeight += weight;
            }

            if (totalWeight <= 0 || !Double.isFinite(totalWeight)) {
                throw new IllegalStateException(
                        "Invalid sampling weights; increase temperature"
                );
            }

            double draw = random.nextDouble() * totalWeight;
            double cumulativeWeight = 0.0;
            int selectedIndex = pool.size() - 1;

            for (int i = 0; i < weights.length; i++) {
                cumulativeWeight += weights[i];

                if (draw < cumulativeWeight) {
                    selectedIndex = i;
                    break;
                }
            }

            // Removing the chosen hit prevents selecting it twice.
            selected.add(pool.remove(selectedIndex));
        }

        return selected;
    }

    public record LexicalHit(
            Long fragmentId,
            float score
    ) implements Comparable<LexicalHit> {

        @Override
        public int compareTo(LexicalHit other) {
            return Float.compare(this.score, other.score);
        }
    }

    private record ScoredEdge(
            SemanticEdge edge,
            double npmi
    ) {}

    public record GraphHit(Long fragmentId, Long fragNodeId) {
    }
}
