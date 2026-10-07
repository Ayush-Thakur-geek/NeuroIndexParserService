package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.models.SemanticEdge;
import com.NeuroIndex.entity.models.SemanticFragment;
import com.NeuroIndex.entity.models.SemanticFragmentNode;
import com.NeuroIndex.entity.models.SemanticNode;
import com.NeuroIndex.parser.dtos.PhraseToNodeDTO;
import com.NeuroIndex.parser.repositories.SemanticEdgeRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentNodeRepo;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.repositories.SemanticNodeRepo;
import com.NeuroIndex.parser.service.GraphFormationService;
import com.NeuroIndex.parser.service.HashingSHA256Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import redis.clients.jedis.UnifiedJedis;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Log4j2
public class GraphFormationServiceImpl implements GraphFormationService {

    private static final int GRAPH_BATCH_SIZE = 100;

    private static final String FRAGMENT_NODES_KEY =
            "graph:fragment:nodes:";

    private static final String NODE_LABELS_KEY =
            "graph:node:labels:";

    private static final String DIRECT_EDGE_COUNTS_KEY =
            "graph:direct:counts:";

    private static final String PHRASES_TO_KEYWORDS_KEY =
            "phrasesToKeywords:user:";

    private static final String WORD_TO_PHRASE =
            "wordsToPhrases:user:";

    private final UnifiedJedis jedis;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private final SemanticNodeRepo semanticNodeRepo;
    private final SemanticFragmentNodeRepo semanticFragmentNodeRepo;
    private final SemanticEdgeRepo semanticEdgeRepo;
    private final ObjectMapper objectMapper;
    private final HashingSHA256Service hashingService;

    @Override
    public void initialPreparations(
            Long userId,
            Long semanticFragmentId,
            Set<PhraseToNodeDTO> phraseDtos,
            Set<String> keywords
    ) {
        if (userId == null
                || semanticFragmentId == null
                || phraseDtos == null
                || phraseDtos.isEmpty()) {
            return;
        }

        /*
         * Deduplicate explicitly by hash. This does not depend on
         * PhraseToNodeDTO.equals()/hashCode().
         */
        Map<String, PhraseToNodeDTO> phrasesByHash =
                phraseDtos.stream()
                        .filter(Objects::nonNull)
                        .filter(dto -> dto.getHash() != null)
                        .filter(dto -> !dto.getHash().isBlank())
                        .filter(dto -> dto.getNounPhrase() != null)
                        .filter(dto -> !dto.getNounPhrase().isBlank())
                        .collect(Collectors.toMap(
                                PhraseToNodeDTO::getHash,
                                Function.identity(),
                                (first, duplicate) -> first,
                                LinkedHashMap::new
                        ));

        List<PhraseToNodeDTO> phrases =
                new ArrayList<>(phrasesByHash.values());

        if (phrases.isEmpty()) {
            return;
        }

        String fragmentKey =
                fragmentNodesKey(userId, semanticFragmentId);

        String labelsKey =
                nodeLabelsKey(userId);

        String directCountsKey =
                directEdgeCountsKey(userId);

        /*
         * Record:
         *
         * fragment -> phrase hashes
         * phrase hash -> normalized phrase label
         */
        for (PhraseToNodeDTO phrase : phrases) {
            jedis.sadd(
                    fragmentKey,
                    phrase.getHash()
            );

            jedis.hset(
                    labelsKey,
                    phrase.getHash(),
                    phrase.getNounPhrase()
            );
        }

        /*
         * Every unique phrase pair inside the fragment is directly related.
         *
         * A-B and B-A are represented by the same canonical pair.
         */
        for (int i = 0; i < phrases.size(); i++) {
            for (int j = i + 1; j < phrases.size(); j++) {
                String firstHash =
                        phrases.get(i).getHash();

                String secondHash =
                        phrases.get(j).getHash();

                if (firstHash.equals(secondHash)) {
                    continue;
                }

                String pair =
                        canonicalPair(firstHash, secondHash);

                jedis.hincrBy(
                        directCountsKey,
                        pair,
                        1L
                );
            }
        }

        /*
         * Phrase-keyword associations are intentionally not created here.
         * They are already determined precisely in
         * filterPhrasesByKeywordOverlap().
         *
         * Mapping every keyword here to every phrase would create false
         * aliases.
         */
    }

    @Override
    @Transactional
    public void initiateGraphFormation(
            Long userId
    ) throws JsonProcessingException {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID is required for graph formation"
            );
        }

        log.info(
                "Started graph formation for userId={}",
                userId
        );

        List<SemanticFragment> fragments =
                semanticFragmentRepo.findAllByUserId(
                        userId
                );

        if (fragments == null || fragments.isEmpty()) {
            log.info(
                    "No semantic fragments found for userId={}",
                    userId
            );
            return;
        }

        Map<String, String> directCounts =
                jedis.hgetAll(
                        directEdgeCountsKey(userId)
                );

        GraphFormationReport directReport =
                directEdgeFormation(
                        userId,
                        fragments,
                        directCounts
                );

        log.info(
                "Completed direct graph formation for userId={}. "
                        + "nodes={}, fragmentMappings={}, directEdges={}",
                userId,
                directReport.nodesProcessed(),
                directReport.fragmentMappingsCreated(),
                directReport.edgesProcessed()
        );

        Map<String, String> labelsByHash =
                jedis.hgetAll(
                        nodeLabelsKey(userId)
                );

        String phraseKeywordsKey = phrasesToKeywordsKey(userId);

        Map<String, Set<String>> keywordsByPhrase =
                deserializeStringSetMap(
                        phraseKeywordsKey,
                        jedis.hgetAll(phraseKeywordsKey)
                );

        String keywordPhrasesKey = wordToPhrasesKey(userId);

        Map<String, Set<String>> phrasesByKeyword =
                deserializeStringSetMap(
                        keywordPhrasesKey,
                        jedis.hgetAll(keywordPhrasesKey)
                );

        GraphFormationReport indirectReport =
                indirectEdgeFormation(
                        userId,
                        fragments,
                        phrasesByKeyword,
                        keywordsByPhrase,
                        labelsByHash
                );

        log.info(
                "Completed indirect graph formation for userId={}. "
                        + "nodes={}, indirectEdges={}",
                userId,
                indirectReport.nodesProcessed(),
                indirectReport.edgesProcessed()
        );

        log.info(
                "Completed complete graph formation for userId={}, fragments={}",
                userId,
                fragments.size()
        );
    }

    private GraphFormationReport directEdgeFormation(
            Long userId, List<SemanticFragment> fragments, Map<String, String> directCounts
    ) throws JsonProcessingException {

        if (fragments == null || fragments.isEmpty()) {
            return new GraphFormationReport(0, 0, 0);
        }

        List<SemanticFragmentNode> mappingsToBeSaved = new ArrayList<>();

        Map<String, SemanticNode> nodesByLabel =
                semanticNodeRepo.findAllByUserId(userId)
                        .stream()
                        .collect(Collectors.toMap(
                                SemanticNode::getLabel,
                                Function.identity()
                        ));

        if (fragments == null || fragments.isEmpty()) {
            log.info(
                    "No semantic fragments found for userId={}",
                    userId
            );

            return new GraphFormationReport(0, 0, 0);
        }

        String labelsKey =
                nodeLabelsKey(userId);

        /*
         * Local cache for this graph-formation run:
         *
         * phraseHash -> persisted SemanticNode
         */
        Map<String, SemanticNode> nodesByHash =
                new HashMap<>();

        int fragmentMappingsCreated = 0;

        /*
         * Pass 1:
         *
         * 1. Resolve each fragment's phrase hashes.
         * 2. Create/reuse SemanticNode records.
         * 3. Create SemanticFragmentNode records.
         */

        List<Long> fragmentIds = fragments.stream()
                .filter(Objects::nonNull)
                .map(SemanticFragment::getId)
                .filter(Objects::nonNull)
                .toList();

        Set<FragmentNodeKey> existingMappings =
                semanticFragmentNodeRepo
                        .findAllByFragment_IdIn(fragmentIds)
                        .stream()
                        .map(mapping -> new FragmentNodeKey(
                                mapping.getFragment().getId(),
                                mapping.getNode().getId()
                        ))
                        .collect(Collectors.toSet());
        for (SemanticFragment fragment : fragments) {
            if (fragment == null || fragment.getId() == null) {
                continue;
            }

            log.info("Direct edge formation for userId={} and for fragmentId={}.", userId, fragment.getId());

            String fragmentKey =
                    fragmentNodesKey(
                            userId,
                            fragment.getId()
                    );

            Set<String> phraseHashes =
                    jedis.smembers(fragmentKey);

            if (phraseHashes == null || phraseHashes.isEmpty()) {
                continue;
            }

            for (String phraseHash : phraseHashes) {
                if (phraseHash == null || phraseHash.isBlank()) {
                    continue;
                }

                SemanticNode node =
                        nodesByHash.get(phraseHash);

                if (node == null) {
                    String label =
                            jedis.hget(
                                    labelsKey,
                                    phraseHash
                            );

                    if (label == null || label.isBlank()) {
                        log.warn(
                                "Missing phrase label for userId={}, " +
                                        "fragmentId={}, phraseHash={}",
                                userId,
                                fragment.getId(),
                                phraseHash
                        );
                        continue;
                    }

                    node = findOrCreateNode(
                            userId,
                            label,
                            nodesByLabel
                    );

                    nodesByHash.put(
                            phraseHash,
                            node
                    );
                }
                FragmentNodeKey mappingKey = new FragmentNodeKey(
                        fragment.getId(),
                        node.getId()
                );

                if (!existingMappings.add(mappingKey)) {
                    continue;
                }

                SemanticFragmentNode mapping =
                        SemanticFragmentNode.builder()
                                .fragment(fragment)
                                .node(node)
                                .weight(1.0f)
                                .build();

//                semanticFragmentNodeRepo.save(mapping);
                mappingsToBeSaved.add(mapping);

                int currentFragmentCount =
                        node.getFragmentCount() == null
                                ? 0
                                : node.getFragmentCount();

                node.setFragmentCount(
                        currentFragmentCount + 1
                );

//                semanticNodeRepo.save(node);

                fragmentMappingsCreated++;
            }
        }

        semanticFragmentNodeRepo.saveAll(mappingsToBeSaved);

        /*
         * Pass 2:
         *
         * The Redis hash contains:
         *
         * phraseHashA|phraseHashB -> co-occurrence count
         */

        int edgesProcessed = 0;

        if (directCounts != null && !directCounts.isEmpty()) {
//            Map<String, SemanticEdge> pairToEdges = new HashMap<>();
            Map<EdgeKey, SemanticEdge> edgesByPair =
                    semanticEdgeRepo.findAllBySourceNode_UserId(userId)
                            .stream()
                            .collect(Collectors.toMap(
                                    edge -> EdgeKey.canonical(
                                            edge.getSourceNode().getId(),
                                            edge.getTargetNode().getId()
                                    ),
                                    Function.identity()
                            ));

            List<SemanticEdge> newEdges = new ArrayList<>();
            for (Map.Entry<String, String> entry :
                    directCounts.entrySet()) {

                String pair = entry.getKey();
                String countValue = entry.getValue();

                if (pair == null || pair.isBlank()) {
                    continue;
                }

                String[] pairParts =
                        pair.split("\\|", 2);

                if (pairParts.length != 2) {
                    log.warn(
                            "Invalid direct-edge pair for userId={}: {}",
                            userId,
                            pair
                    );
                    continue;
                }

                String firstHash = pairParts[0];
                String secondHash = pairParts[1];

                if (firstHash.equals(secondHash)) {
                    continue;
                }

                long cooccurrenceCount;

                try {
                    cooccurrenceCount =
                            Long.parseLong(countValue);
                } catch (NumberFormatException exception) {
                    log.warn(
                            "Invalid co-occurrence count for pair {}: {}",
                            pair,
                            countValue
                    );
                    continue;
                }

                if (cooccurrenceCount <= 0) {
                    continue;
                }

                SemanticNode firstNode =
                        resolveNode(
                                userId,
                                firstHash,
                                labelsKey,
                                nodesByHash,
                                nodesByLabel
                        );

                SemanticNode secondNode =
                        resolveNode(
                                userId,
                                secondHash,
                                labelsKey,
                                nodesByHash,
                                nodesByLabel
                        );

                if (firstNode == null || secondNode == null) {
                    log.warn(
                            "Could not resolve both nodes for pair={}",
                            pair
                    );
                    continue;
                }

                if (firstNode.getId().equals(secondNode.getId())) {
                    continue;
                }

                /*
                 * Store the database edge in canonical node-ID order.
                 */
                SemanticNode sourceNode;
                SemanticNode targetNode;

                if (firstNode.getId() < secondNode.getId()) {
                    sourceNode = firstNode;
                    targetNode = secondNode;
                } else {
                    sourceNode = secondNode;
                    targetNode = firstNode;
                }

                upsertDirectEdge(
                        sourceNode,
                        targetNode,
                        edgesByPair,
                        newEdges,
                        cooccurrenceCount
                );

                edgesProcessed++;
            }
            semanticEdgeRepo.saveAll(newEdges);
        }

        return new GraphFormationReport(
                nodesByHash.size(),
                fragmentMappingsCreated,
                edgesProcessed
        );
    }

    private GraphFormationReport indirectEdgeFormation(
            Long userId,
            List<SemanticFragment> fragments,
            Map<String, Set<String>> phrasesByKeyword,
            Map<String, Set<String>> keywordsByPhrase,
            Map<String, String> labelsByHash
    ) throws JsonProcessingException {

        /*
         * Load the nodes once for this method call.
         *
         * Previously this query ran once for every candidate pair.
         */
        Map<String, SemanticNode> nodesByLabel =
                semanticNodeRepo.findAllByUserId(userId)
                        .stream()
                        .collect(Collectors.toMap(
                                SemanticNode::getLabel,
                                Function.identity(),
                                (first, duplicate) -> first
                        ));

        Set<String> sourcePhrases =
                fragments.stream()
                        .filter(Objects::nonNull)
                        .map(SemanticFragment::getNounPhrases)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .filter(Objects::nonNull)
                        .map(this::normalizePhrase)
                        .filter(phrase -> !phrase.isBlank())
                        .filter(keywordsByPhrase::containsKey)
                        .collect(Collectors.toSet());

        Set<NodePair> processedPairs =
                new HashSet<>();

        int edgesProcessed = 0;

        for (String sourcePhrase : sourcePhrases) {
            String sourceHash =
                    hashingService.hash(sourcePhrase);

            String sourceLabel =
                    labelsByHash.get(sourceHash);

            /*
             * Keep your intended behavior:
             * only process phrases represented in the direct-node mapping.
             */
            if (sourceLabel == null
                    || !sourceLabel.equals(sourcePhrase)) {
                continue;
            }

            Set<String> sourceKeywords =
                    keywordsByPhrase.getOrDefault(
                            sourcePhrase,
                            Collections.emptySet()
                    );

            if (sourceKeywords.isEmpty()) {
                continue;
            }

            Set<String> candidatePhrases =
                    new HashSet<>();

            for (String keyword : sourceKeywords) {
                candidatePhrases.addAll(
                        phrasesByKeyword.getOrDefault(
                                keyword,
                                Collections.emptySet()
                        )
                );
            }

            candidatePhrases.remove(sourcePhrase);

            for (String targetPhrase : candidatePhrases) {
                if (targetPhrase == null
                        || targetPhrase.isBlank()) {
                    continue;
                }

                String normalizedTargetPhrase =
                        normalizePhrase(targetPhrase);

                if (normalizedTargetPhrase.isBlank()
                        || normalizedTargetPhrase.equals(sourcePhrase)) {
                    continue;
                }

                Set<String> targetKeywords =
                        keywordsByPhrase.getOrDefault(
                                normalizedTargetPhrase,
                                Collections.emptySet()
                        );

                if (targetKeywords.isEmpty()) {
                    continue;
                }

                NodePair pair =
                        NodePair.canonical(
                                sourcePhrase,
                                normalizedTargetPhrase
                        );

                if (!processedPairs.add(pair)) {
                    continue;
                }

                Set<String> sharedKeywords =
                        new HashSet<>(sourceKeywords);

                sharedKeywords.retainAll(
                        targetKeywords
                );

                if (sharedKeywords.isEmpty()) {
                    continue;
                }

                Set<String> unionKeywords =
                        new HashSet<>(sourceKeywords);

                unionKeywords.addAll(
                        targetKeywords
                );

                float overlapScore =
                        (float) sharedKeywords.size()
                                / unionKeywords.size();

                SemanticNode sourceNode =
                        nodesByLabel.get(pair.first());

                SemanticNode targetNode =
                        nodesByLabel.get(pair.second());

                log.info("forming indirect edge between {} and {}", sourceNode.getId(), targetNode.getId());

                if (sourceNode == null || targetNode == null) {
                    log.warn(
                            "Skipping indirect edge because node is missing: {} -> {}",
                            pair.first(),
                            pair.second()
                    );
                    continue;
                }

                upsertIndirectEdge(
                        sourceNode,
                        targetNode,
                        sharedKeywords.size(),
                        0.0f, // Calculate IDF dynamically during search.
                        overlapScore
                );

                edgesProcessed++;
            }
        }

        return new GraphFormationReport(
                sourcePhrases.size(),
                0,
                edgesProcessed
        );
    }

    private Map<String, Set<String>> deserializeStringSetMap(
            String redisKey,
            Map<String, String> jsonMap
    ) throws JsonProcessingException {

        Map<String, Set<String>> result = new HashMap<>();

        for (Map.Entry<String, String> entry : jsonMap.entrySet()) {
            String json = entry.getValue();

            if (json == null || json.isBlank()) {
                continue;
            }

            JsonNode root = objectMapper.readTree(json);

            if (root == null || !root.isArray()) {
                throw new IllegalStateException(
                        "Expected a JSON array in Redis. key="
                                + redisKey
                                + ", field=" + entry.getKey()
                                + ", actualType="
                                + (root == null ? "EMPTY" : root.getNodeType())
                );
            }

            List<String> values = objectMapper.readValue(
                    json,
                    new TypeReference<List<String>>() {}
            );

            result.put(entry.getKey(), new HashSet<>(values));
        }

        return result;
    }

    private record NodePair(
            String first,
            String second
    ) {
        private static NodePair canonical(
                String first,
                String second
        ) {
            return first.compareTo(second) < 0f
                    ? new NodePair(first, second)
                    : new NodePair(second, first);
        }
    }

    private SemanticNode resolveNode(
            Long userId,
            String phraseHash,
            String labelsKey,
            Map<String, SemanticNode> nodesByHash,
            Map<String, SemanticNode> nodesByLabel
    ) throws JsonProcessingException {
        SemanticNode cachedNode =
                nodesByHash.get(phraseHash);

        if (cachedNode != null) {
            return cachedNode;
        }

        String label =
                jedis.hget(
                        labelsKey,
                        phraseHash
                );

        if (label == null || label.isBlank()) {
            return null;
        }

        SemanticNode node =
                findOrCreateNode(
                        userId,
                        label,
                        nodesByLabel
                );

        nodesByHash.put(
                phraseHash,
                node
        );

        return node;
    }

    private SemanticNode findOrCreateNode(
            Long userId,
            String label,
            Map<String, SemanticNode> nodesByLabel
    ) throws JsonProcessingException {

        SemanticNode existingNode = nodesByLabel.get(label);

        if (existingNode != null) {
            return existingNode;
        }

        SemanticNode node = SemanticNode.builder()
                .userId(userId)
                .label(label)
                .centroid(createPhraseCentroidJson(userId, label))
                .fragmentCount(0)
                .avgSimilarity(0.0f)
                .isStable(true)
                .build();

        SemanticNode savedNode = semanticNodeRepo.save(node);
        nodesByLabel.put(label, savedNode);

        return savedNode;
    }

    private String createPhraseCentroidJson(
            Long userId,
            String phrase
    ) throws JsonProcessingException {
        float[] centroid =
                getPhraseCentroid(
                        userId,
                        phrase
                );

        if (centroid == null || centroid.length == 0) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(
                    centroid
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to serialize centroid for phrase: "
                            + phrase,
                    exception
            );
        }
    }

    private void upsertIndirectEdge(
            SemanticNode sourceNode,
            SemanticNode targetNode,
            int sharedKeywordCount,
            float sharedKeywordIdf,
            float overlapScore
    ) {
        SemanticNode canonicalSource;
        SemanticNode canonicalTarget;

        if (sourceNode.getId().compareTo(targetNode.getId()) < 0) {
            canonicalSource = sourceNode;
            canonicalTarget = targetNode;
        } else {
            canonicalSource = targetNode;
            canonicalTarget = sourceNode;
        }

        Optional<SemanticEdge> existingEdge =
                semanticEdgeRepo
                        .findBySourceNode_IdAndTargetNode_Id(
                                canonicalSource.getId(),
                                canonicalTarget.getId()
                        );

        if (existingEdge.isPresent()) {
            SemanticEdge edge = existingEdge.get();

            /*
             * These values were calculated from the complete keyword sets,
             * so assign rather than increment. This makes the operation
             * idempotent across repeated graph projections.
             */
            edge.setSharedKeywordCount(
                    sharedKeywordCount
            );

            edge.setSharedKeywordIdf(
                    sharedKeywordIdf
            );

            edge.setOverlapScore(
                    overlapScore
            );

            edge.setFusedWeight(null);
            edge.setWeightsEpoch(null);

            semanticEdgeRepo.save(edge);
            return;
        }

        SemanticEdge edge =
                SemanticEdge.builder()
                        .sourceNode(canonicalSource)
                        .targetNode(canonicalTarget)
                        .cooccurrenceCount(0L)
                        .sharedKeywordCount(sharedKeywordCount)
                        .sharedKeywordIdf(sharedKeywordIdf)
                        .learnedConfirmations(0)
                        .npmi(null)
                        .overlapScore(overlapScore)
                        .fusedWeight(null)
                        .weightsEpoch(null)
                        .build();

        semanticEdgeRepo.save(edge);
    }

    private void upsertDirectEdge(
            SemanticNode sourceNode,
            SemanticNode targetNode,
            Map<EdgeKey, SemanticEdge> edgesByPair,
            List<SemanticEdge> newEdges,
            long cooccurrenceCount
    ) {
        EdgeKey key = EdgeKey.canonical(
                sourceNode.getId(),
                targetNode.getId()
        );

        SemanticEdge edge = edgesByPair.get(key);

        if (edge == null) {
            edge = SemanticEdge.builder()
                    .sourceNode(sourceNode)
                    .targetNode(targetNode)
                    .cooccurrenceCount(0L)
                    .sharedKeywordCount(0)
                    .sharedKeywordIdf(0.0f)
                    .learnedConfirmations(0)
                    .build();

            // If SemanticEdge has a required userId field:
            // edge.setUserId(sourceNode.getUserId());

            edgesByPair.put(key, edge);
            newEdges.add(edge);
        }

        edge.setCooccurrenceCount(cooccurrenceCount);
        edge.setNpmi(null);
        edge.setFusedWeight(null);
        edge.setWeightsEpoch(null);
    }

    public float[] getPhraseCentroid(Long userId, String phrase) throws JsonProcessingException {

        String redisKey = "centroids:user:" + userId;

        List<float[]> wordCentroids = new ArrayList<>();

        String redisKeyForGettingKeywords = "phrasesToKeywords:user:" + userId;

        String keyWordJson = jedis.hget(redisKeyForGettingKeywords, phrase);

        List<String> keywords = new ArrayList<>();
        if (keyWordJson != null) {
            keywords = objectMapper.readValue(keyWordJson, List.class);
        }

        for (String word : keywords) {
            String json = jedis.hget(redisKey, word);
            if (json == null) continue;
            wordCentroids.add(objectMapper.readValue(json, float[].class));
        }

        if (wordCentroids.isEmpty()) return null; // no centroid evidence yet for any word

        return averageVectors(wordCentroids);
    }

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


    private String canonicalPair(
            String first,
            String second
    ) {
        return first.compareTo(second) < 0
                ? first + "|" + second
                : second + "|" + first;
    }

    private String normalizePhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return "";

        return Arrays.stream(phrase.trim().split("\\s+"))
                .map(this::normalizeWords)
                .filter(w -> !w.isEmpty())
                .collect(Collectors.joining(" "));
    }

    private String normalizeWords(String word) {
        if (word == null) return "";
        return word.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", ""); // strip underscores, punctuation, etc.
    }

    private String fragmentNodesKey(
            Long userId,
            Long fragmentId
    ) {
        return FRAGMENT_NODES_KEY
                + userId
                + ":"
                + fragmentId;
    }

    private String nodeLabelsKey(
            Long userId
    ) {
        return NODE_LABELS_KEY + userId;
    }

    private String directEdgeCountsKey(
            Long userId
    ) {
        return DIRECT_EDGE_COUNTS_KEY + userId;
    }

    private String phrasesToKeywordsKey(
            Long userId
    ) {
        return PHRASES_TO_KEYWORDS_KEY + userId;
    }

    private String wordToPhrasesKey(
            Long userId
    ) {
        return WORD_TO_PHRASE + userId;
    }

    private record GraphFormationReport(
            int nodesProcessed,
            int fragmentMappingsCreated,
            int edgesProcessed
    ) {
    }

    private record EdgeKey(Long sourceId, Long targetId) {

        static EdgeKey canonical(Long first, Long second) {
            return first.compareTo(second) < 0
                    ? new EdgeKey(first, second)
                    : new EdgeKey(second, first);
        }
    }

    private record FragmentNodeKey(
            Long fragmentId,
            Long nodeId
    ) {
    }
}