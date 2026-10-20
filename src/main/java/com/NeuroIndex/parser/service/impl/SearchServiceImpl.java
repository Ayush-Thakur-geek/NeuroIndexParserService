package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.models.SemanticEdge;
import com.NeuroIndex.entity.models.SemanticFragmentNode;
import com.NeuroIndex.entity.models.SemanticNode;
import com.NeuroIndex.parser.helperClasses.SearchJobStore;
import com.NeuroIndex.parser.repositories.SemanticEdgeRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentNodeRepo;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class SearchServiceImpl implements SearchService {

    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private final SearchJobStore searchJobStore;

    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final int DB_BATCH_SIZE = 500;
    private static final double TEMPERATURE = 0.4;
    private static final int TOP_RESULT_PERCENT = 50;
    private final SemanticFragmentNodeRepo semanticFragmentNodeRepo;
    private final SemanticEdgeRepo semanticEdgeRepo;
    private static final int DEPTH = 3;
    private static final int MAX_GRAPH_NODES = 1_000;
    private static final int MAX_DIRECT_NEIGHBORS = 3;
    private final PlatformTransactionManager transactionManager;
    private static final int RRF_K = 60;

    @Override
    public SearchJobStore.SearchTicket fastSearch(
            String query,
            Long userId
    ) throws ExecutionException {

        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User ID must be positive"
            );
        }

        if (query == null
                || query.isBlank()
                || query.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Query must contain 1-500 characters"
            );
        }

        long started = System.nanoTime();

        List<LexicalHit> luceneResults;

        try {
            luceneResults = searchLucene(
                    userId,
                    query.trim(),
                    50
            );
        } catch (IOException exception) {
            log.error(
                    "Lucene search failed for userId={}",
                    userId,
                    exception
            );

            throw new ExecutionException(exception);
        }

        Map<Long, LexicalHit> uniqueHits = new LinkedHashMap<>();

        for (LexicalHit hit : luceneResults) {
            uniqueHits.putIfAbsent(hit.fragmentId(), hit);
        }

        List<LexicalHit> rankedHits =
                List.copyOf(uniqueHits.values());

        PreparedMessages initial = readOnly(
                5,
                false,
                () -> prepareMessages(
                        userId,
                        rankedHits,
                        List.of(),
                        null
                )
        );

        List<LexicalHit> graphSeeds =
                selectGraphSeeds(rankedHits);

        SearchJobStore.SearchTicket ticket =
                searchJobStore.start(
                        userId,
                        initial.results(),
                        !rankedHits.isEmpty(),
                        control -> readOnly(
                                15,
                                true,
                                () -> {
                                    control.checkpoint();

                                    long totalFragments =
                                            semanticFragmentRepo.countByUserId(
                                                    userId
                                            );

                                    List<GraphHit> graphHits =
                                            totalFragments == 0
                                                    ? List.of()
                                                    : graphTraversal(
                                                    userId,
                                                    graphSeeds,
                                                    totalFragments
                                            );

                                    control.checkpoint();

                                    PreparedMessages refined =
                                            prepareMessages(
                                                    userId,
                                                    rankedHits,
                                                    graphHits,
                                                    initial
                                            );

                                    control.checkpoint();

                                    return refined.results();
                                }
                        )
                );

        log.info(
                "Initial search ready: userId={}, searchId={}, "
                        + "lexicalHits={}, messages={}, elapsedMs={}",
                userId,
                ticket.searchId(),
                rankedHits.size(),
                initial.results().size(),
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started
                )
        );

        return ticket;
    }

    @Override
    public SearchJobStore.SearchTicket getRefinement(
            Long userId,
            UUID searchId
    ) {
        return searchJobStore.get(userId, searchId);
    }

    private List<LexicalHit> selectGraphSeeds(
            List<LexicalHit> rankedHits
    ) {
        int seedCount = rankedHits.size();

        if (seedCount > 20) {
            seedCount /= 2;
        }

        int deterministicCount =
                TOP_RESULT_PERCENT * seedCount / 100;

        List<LexicalHit> seeds = new ArrayList<>(
                rankedHits.subList(0, deterministicCount)
        );

        seeds.addAll(
                sampleWithoutReplacement(
                        new ArrayList<>(
                                rankedHits.subList(
                                        deterministicCount,
                                        rankedHits.size()
                                )
                        ),
                        seedCount - deterministicCount,
                        TEMPERATURE,
                        ThreadLocalRandom.current()
                )
        );

        seeds.sort(
                Comparator.comparingDouble(LexicalHit::score)
                        .reversed()
        );

        return List.copyOf(seeds);
    }

    @Override
    public List<MessageSearchResponse> extractMessage(
            Long userId,
            List<LexicalHit> rankedHits,
            List<GraphHit> graphHits
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "User ID must be positive"
            );
        }

        return readOnly(
                5,
                false,
                () -> prepareMessages(
                        userId,
                        rankedHits,
                        graphHits,
                        null
                ).results()
        );
    }

    private PreparedMessages prepareMessages(
            Long userId,
            List<LexicalHit> rankedHits,
            List<GraphHit> graphHits,
            PreparedMessages previous
    ) {
        Map<Long, Long> fragmentToMessage =
                previous == null
                        ? new HashMap<>()
                        : new HashMap<>(previous.fragmentToMessage());

        Map<Long, List<SemanticUnit>> messageContent =
                previous == null
                        ? new HashMap<>()
                        : new HashMap<>(previous.messageContent());

        Set<Long> missingFragments = new LinkedHashSet<>();

        rankedHits.forEach(
                hit -> missingFragments.add(hit.fragmentId())
        );

        graphHits.forEach(
                hit -> missingFragments.add(hit.fragmentId())
        );

        missingFragments.removeAll(fragmentToMessage.keySet());

        for (List<Long> batch : idBatches(missingFragments)) {
            List<SemanticFragmentRepo.FragmentMessageLink> rows =
                    semanticFragmentRepo.findSearchMessageLinks(
                            userId,
                            batch
                    );

            for (SemanticFragmentRepo.FragmentMessageLink row : rows) {
                fragmentToMessage.put(
                        row.getFragmentId(),
                        row.getMessageId()
                );
            }
        }

        List<Long> lexicalMessageIds = rankedHits.stream()
                .map(hit -> fragmentToMessage.get(hit.fragmentId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Long> graphMessageIds = graphHits.stream()
                .map(hit -> fragmentToMessage.get(hit.fragmentId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<RrfRank> rankedMessages = rrf(
                lexicalMessageIds,
                graphMessageIds
        ).stream().limit(SEARCH_RESULT_LIMIT).toList();

        Set<Long> missingMessages = rankedMessages.stream()
                .map(RrfRank::messageId)
                .filter(id -> !messageContent.containsKey(id))
                .collect(
                        Collectors.toCollection(LinkedHashSet::new)
                );

        for (List<Long> batch : idBatches(missingMessages)) {
            List<SemanticFragmentRepo.SearchMessageContent> rows =
                    semanticFragmentRepo.findSearchMessageContents(
                            userId,
                            batch
                    );

            for (SemanticFragmentRepo.SearchMessageContent row : rows) {
                messageContent.put(
                        row.getMessageId(),
                        row.getMessageContent() == null
                                ? List.of()
                                : List.copyOf(row.getMessageContent())
                );
            }
        }

        List<MessageSearchResponse> results = rankedMessages.stream()
                .filter(rank -> messageContent.containsKey(rank.messageId()))
                .map(rank -> new MessageSearchResponse(
                        rank.messageId(),
                        rank.score(),
                        messageContent.get(rank.messageId())
                ))
                .toList();

        return new PreparedMessages(
                Map.copyOf(fragmentToMessage),
                Map.copyOf(messageContent),
                results
        );
    }

    private List<RrfRank> rrf(
            List<Long> luceneMessageIds,
            List<Long> graphMessageIds
    ) {
        Map<Long, Double> fusedScores = new HashMap<>();

        addRrfContributions(luceneMessageIds, fusedScores);
        addRrfContributions(graphMessageIds, fusedScores);

        return fusedScores.entrySet()
                .stream()
                .map(entry -> new RrfRank(
                        entry.getKey(),
                        entry.getValue()
                ))
                .sorted(
                        Comparator.comparingDouble(RrfRank::score)
                                .reversed()
                                .thenComparing(RrfRank::messageId)
                )
                .toList();
    }

    private void addRrfContributions(
            List<Long> rankedMessageIds,
            Map<Long, Double> fusedScores
    ) {
        for (int index = 0; index < rankedMessageIds.size(); index++) {
            int rank = index + 1;

            double contribution =
                    1.0 / (RRF_K + rank);

            fusedScores.merge(
                    rankedMessageIds.get(index),
                    contribution,
                    Double::sum
            );
        }
    }


    private List<GraphHit> graphTraversal(
            Long userId,
            List<LexicalHit> graphSeeds,
            long totalFragments
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.setReadOnly(true);

        List<GraphHit> hits = transaction.execute(status -> {
            checkGraphInterrupted();

            Map<Long, Float> fragmentScores =
                    graphSeeds.stream()
                            .collect(Collectors.toMap(
                                    LexicalHit::fragmentId,
                                    LexicalHit::score,
                                    (first, second) -> Math.max(first, second)
                            ));

            if (fragmentScores.isEmpty()) {
                return List.of();
            }

            List<SemanticFragmentNode> mappings =
                    semanticFragmentNodeRepo.findSeedMappings(
                            userId,
                            fragmentScores.keySet()
                    );

            Map<Long, SemanticNode> roots = new LinkedHashMap<>();
            Map<Long, Double> seedNodeScores = new HashMap<>();

            for (SemanticFragmentNode mapping : mappings) {
                SemanticNode node = mapping.getNode();

                if (!Objects.equals(node.getUserId(), userId)) {
                    continue;
                }

                Float fragmentScore = fragmentScores.get(
                        mapping.getFragment().getId()
                );

                if (fragmentScore == null
                        || !Float.isFinite(fragmentScore)
                        || fragmentScore <= 0.0f) {
                    continue;
                }

                roots.putIfAbsent(node.getId(), node);

                // A node can be introduced by several seed fragments.
                seedNodeScores.merge(
                        node.getId(),
                        fragmentScore.doubleValue(),
                        Math::max
                );
            }

            log.info(
                    "Graph seeds resolved: userId={}, seedFragments={}, seedNodes={}",
                    userId,
                    fragmentScores.size(),
                    roots.size()
            );

            if (roots.isEmpty()) {
                return List.of();
            }

            return traverse(
                    userId,
                    roots.values(),
                    seedNodeScores,
                    totalFragments
            );
        });

        return hits == null ? List.of() : hits;
    }

    private List<GraphHit> traverse(
            Long userId,
            Collection<SemanticNode> roots,
            Map<Long, Double> seedNodeScores,
            long totalFragments
    ) {
        Map<Long, ScoredNode> frontier = new LinkedHashMap<>();

        Map<Long, Double> bestNodeScores = new HashMap<>();

        Map<Long, List<ScoredEdge>> edgeCache = new HashMap<>();

        List<SemanticNode> orderedRoots = roots.stream()
                .sorted(
                        Comparator.comparingDouble(
                                        (SemanticNode node) ->
                                                seedNodeScores.getOrDefault(
                                                        node.getId(),
                                                        0.0
                                                )
                                ).reversed()
                                .thenComparing(SemanticNode::getId)
                )
                .toList();

        boolean capped = false;

        for (SemanticNode root : orderedRoots) {
            if (bestNodeScores.size() >= MAX_GRAPH_NODES) {
                capped = true;
                break;
            }

            double score = seedNodeScores.getOrDefault(
                    root.getId(),
                    0.0
            );

            if (!Double.isFinite(score) || score <= 0) {
                continue;
            }

            frontier.put(
                    root.getId(),
                    new ScoredNode(root, score)
            );

            bestNodeScores.put(root.getId(), score);
        }

        for (int hop = 0;
             hop < DEPTH && !frontier.isEmpty();
             hop++) {

            checkGraphInterrupted();

            long hopStarted = System.nanoTime();

            prefetchEdges(
                    userId,
                    frontier.keySet(),
                    edgeCache,
                    totalFragments
            );

            Map<Long, ScoredNode> nextFrontier =
                    new LinkedHashMap<>();

            for (ScoredNode current : frontier.values()) {
                checkGraphInterrupted();

                SemanticNode node = current.node();

                List<ScoredEdge> edges = edgeCache.getOrDefault(
                        node.getId(),
                        List.of()
                );

                for (ScoredEdge scoredEdge : edges) {
                    checkGraphInterrupted();

                    SemanticEdge edge = scoredEdge.edge();

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
                                "Skipping non-incident edge: edgeId={}, nodeId={}",
                                edge.getId(),
                                node.getId()
                        );

                        continue;
                    }

                    if (!Objects.equals(
                            neighbor.getUserId(),
                            userId
                    )) {
                        log.warn(
                                "Skipping cross-user neighbor: userId={}, nodeId={}",
                                userId,
                                neighbor.getId()
                        );

                        continue;
                    }

                    double nextScore =
                            current.score() * scoredEdge.npmi();

                    if (!Double.isFinite(nextScore)
                            || nextScore <= 0) {
                        continue;
                    }

                    Long neighborId = neighbor.getId();

                    if (!bestNodeScores.containsKey(neighborId)
                            && bestNodeScores.size() >= MAX_GRAPH_NODES) {
                        capped = true;
                        continue;
                    }

                    bestNodeScores.merge(
                            neighborId,
                            nextScore,
                            Math::max
                    );

                    ScoredNode previous =
                            nextFrontier.get(neighborId);

                    if (previous == null
                            || nextScore > previous.score()) {
                        nextFrontier.put(
                                neighborId,
                                new ScoredNode(neighbor, nextScore)
                        );
                    }
                }
            }

            log.info(
                    "Graph hop completed: userId={}, hop={}, "
                            + "frontier={}, nextFrontier={}, nodes={}, elapsedMs={}",
                    userId,
                    hop + 1,
                    frontier.size(),
                    nextFrontier.size(),
                    bestNodeScores.size(),
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - hopStarted
                    )
            );

            frontier = nextFrontier;
        }

        if (capped) {
            log.warn(
                    "Graph traversal reached node cap: userId={}, cap={}",
                    userId,
                    MAX_GRAPH_NODES
            );
        }

        Map<Long, GraphHit> bestFragmentHits = new HashMap<>();

        for (List<Long> batch : idBatches(bestNodeScores.keySet())) {
            checkGraphInterrupted();

            List<SemanticFragmentNodeRepo.GraphPosting> postings =
                    semanticFragmentNodeRepo.findGraphPostings(
                            userId,
                            batch
                    );

            for (SemanticFragmentNodeRepo.GraphPosting posting : postings) {
                double score = bestNodeScores.getOrDefault(
                        posting.getNodeId(),
                        0.0
                );

                if (score <= 0) {
                    continue;
                }

                GraphHit candidate = new GraphHit(
                        posting.getFragmentId(),
                        posting.getMappingId(),
                        score
                );

                bestFragmentHits.merge(
                        candidate.fragmentId(),
                        candidate,
                        (existing, incoming) ->
                                incoming.score() > existing.score()
                                        ? incoming
                                        : existing
                );
            }
        }

        List<GraphHit> rankedHits = bestFragmentHits.values().stream()
                .sorted(
                        Comparator.comparingDouble(GraphHit::score)
                                .reversed()
                                .thenComparing(GraphHit::fragmentId)
                )
                .toList();

        log.info(
                "Graph traversal completed: userId={}, nodes={}, fragments={}",
                userId,
                bestNodeScores.size(),
                rankedHits.size()
        );

        return rankedHits;
    }

    private void checkGraphInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "Graph traversal interrupted"
            );
        }
    }

    private List<ScoredEdge> getEdges(
            SemanticNode node,
            long totalFragments
    ) {
        List<SemanticEdge> connectedEdges =
                semanticEdgeRepo.findAllConnectedToNode(node.getId());

        List<ScoredEdge> scoredEdges = new ArrayList<>();

        for (SemanticEdge edge : connectedEdges) {
            checkGraphInterrupted();

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

    private void prefetchEdges(
            Long userId,
            Collection<Long> frontierNodeIds,
            Map<Long, List<ScoredEdge>> edgeCache,
            long totalFragments
    ) {
        Set<Long> missingNodeIds = frontierNodeIds.stream()
                .filter(id -> !edgeCache.containsKey(id))
                .collect(
                        Collectors.toCollection(LinkedHashSet::new)
                );

        for (List<Long> batch : idBatches(missingNodeIds)) {
            checkGraphInterrupted();

            Set<Long> requested = new HashSet<>(batch);

            Map<Long, List<ScoredEdge>> loaded = new HashMap<>();

            for (Long nodeId : batch) {
                loaded.put(nodeId, new ArrayList<>());
            }

            List<SemanticEdge> edges =
                    semanticEdgeRepo.findAllConnectedToNodes(
                            userId,
                            batch
                    );

            for (SemanticEdge edge : edges) {
                checkGraphInterrupted();

                Double npmi = npmiCalculation(
                        edge,
                        totalFragments
                );

                if (npmi == null || npmi <= 0) {
                    continue;
                }

                ScoredEdge scoredEdge = new ScoredEdge(
                        edge,
                        npmi
                );

                Long sourceId = edge.getSourceNode().getId();
                Long targetId = edge.getTargetNode().getId();

                if (requested.contains(sourceId)) {
                    retainTopEdge(
                            loaded.get(sourceId),
                            scoredEdge
                    );
                }

                if (requested.contains(targetId)) {
                    retainTopEdge(
                            loaded.get(targetId),
                            scoredEdge
                    );
                }
            }

            loaded.forEach(
                    (nodeId, scoredEdges) ->
                            edgeCache.put(
                                    nodeId,
                                    List.copyOf(scoredEdges)
                            )
            );

            log.debug(
                    "Graph frontier loaded: userId={}, nodes={}, edgeRows={}",
                    userId,
                    batch.size(),
                    edges.size()
            );
        }
    }

    private void retainTopEdge(
            List<ScoredEdge> edges,
            ScoredEdge candidate
    ) {
        edges.add(candidate);

        edges.sort(
                Comparator.comparingDouble(ScoredEdge::npmi)
                        .reversed()
                        .thenComparing(
                                scored -> scored.edge().getId()
                        )
        );

        if (edges.size() > MAX_DIRECT_NEIGHBORS) {
            edges.remove(edges.size() - 1);
        }
    }

    private <T> T readOnly(
            int timeoutSeconds,
            boolean stableSnapshot,
            Supplier<T> action
    ) {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        transaction.setReadOnly(true);
        transaction.setTimeout(timeoutSeconds);

        if (stableSnapshot) {
            transaction.setIsolationLevel(
                    TransactionDefinition.ISOLATION_REPEATABLE_READ
            );
        }

        return Objects.requireNonNull(
                transaction.execute(
                        status -> action.get()
                )
        );
    }

    private static List<List<Long>> idBatches(
            Collection<Long> ids
    ) {
        List<Long> values = new ArrayList<>(ids);

        List<List<Long>> batches = new ArrayList<>();

        for (int start = 0;
             start < values.size();
             start += DB_BATCH_SIZE) {

            batches.add(
                    values.subList(
                            start,
                            Math.min(
                                    start + DB_BATCH_SIZE,
                                    values.size()
                            )
                    )
            );
        }

        return batches;
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

    private record ScoredNode(
            SemanticNode node,
            double score
    ) {}

    private record ScoredEdge(
            SemanticEdge edge,
            double npmi
    ) {}

    public record GraphHit(
            Long fragmentId,
            Long mappingId,
            double score
    ) {}

    private record RrfRank(
            Long messageId,
            double score
    ) {}
    public record MessageSearchResponse(
            Long messageId,
            double score,
            List<SemanticUnit> content
    ) {}
    private record PreparedMessages(
            Map<Long, Long> fragmentToMessage,
            Map<Long, List<SemanticUnit>> messageContent,
            List<MessageSearchResponse> results
    ) {
    }
}
