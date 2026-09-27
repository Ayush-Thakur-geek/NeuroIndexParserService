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
import com.fasterxml.jackson.core.JsonProcessingException;
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

    private static final String FRAGMENT_NODES_KEY =
            "graph:fragment:nodes:";

    private static final String NODE_LABELS_KEY =
            "graph:node:labels:";

    private static final String DIRECT_EDGE_COUNTS_KEY =
            "graph:direct:counts:";

    private final UnifiedJedis jedis;
    private final SemanticFragmentRepo semanticFragmentRepo;
    private final SemanticNodeRepo semanticNodeRepo;
    private final SemanticFragmentNodeRepo semanticFragmentNodeRepo;
    private final SemanticEdgeRepo semanticEdgeRepo;
    private final ObjectMapper objectMapper;

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
    public void initiateGraphFormation(Long userId) throws JsonProcessingException {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID is required for graph formation"
            );
        }

        log.info(
                "Started graph formation for userId={}",
                userId
        );

        GraphFormationReport report =
                directEdgeFormation(userId);

        log.info(
                "Completed graph formation for userId={}. " +
                        "nodes={}, fragmentMappings={}, directEdges={}",
                userId,
                report.nodesProcessed(),
                report.fragmentMappingsCreated(),
                report.edgesProcessed()
        );
    }

    private GraphFormationReport directEdgeFormation(
            Long userId
    ) throws JsonProcessingException {
        List<SemanticFragment> fragments =
                semanticFragmentRepo.findAllByUserId(userId);

        if (fragments == null || fragments.isEmpty()) {
            log.info(
                    "No semantic fragments found for userId={}",
                    userId
            );

            return new GraphFormationReport(0, 0, 0);
        }

        String labelsKey =
                nodeLabelsKey(userId);

        String directCountsKey =
                directEdgeCountsKey(userId);

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
        for (SemanticFragment fragment : fragments) {
            if (fragment == null || fragment.getId() == null) {
                continue;
            }

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
                            label
                    );

                    nodesByHash.put(
                            phraseHash,
                            node
                    );
                }

                boolean mappingExists =
                        semanticFragmentNodeRepo
                                .existsByFragment_IdAndNode_Id(
                                        fragment.getId(),
                                        node.getId()
                                );

                if (mappingExists) {
                    continue;
                }

                SemanticFragmentNode mapping =
                        SemanticFragmentNode.builder()
                                .fragment(fragment)
                                .node(node)
                                .weight(1.0f)
                                .build();

                semanticFragmentNodeRepo.save(mapping);

                int currentFragmentCount =
                        node.getFragmentCount() == null
                                ? 0
                                : node.getFragmentCount();

                node.setFragmentCount(
                        currentFragmentCount + 1
                );

                semanticNodeRepo.save(node);

                fragmentMappingsCreated++;
            }
        }

        /*
         * Pass 2:
         *
         * The Redis hash contains:
         *
         * phraseHashA|phraseHashB -> co-occurrence count
         */
        Map<String, String> directCounts =
                jedis.hgetAll(directCountsKey);

        int edgesProcessed = 0;

        if (directCounts != null && !directCounts.isEmpty()) {
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
                                nodesByHash
                        );

                SemanticNode secondNode =
                        resolveNode(
                                userId,
                                secondHash,
                                labelsKey,
                                nodesByHash
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
                        cooccurrenceCount
                );

                edgesProcessed++;
            }
        }

        return new GraphFormationReport(
                nodesByHash.size(),
                fragmentMappingsCreated,
                edgesProcessed
        );
    }

    private SemanticNode resolveNode(
            Long userId,
            String phraseHash,
            String labelsKey,
            Map<String, SemanticNode> nodesByHash
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
                        label
                );

        nodesByHash.put(
                phraseHash,
                node
        );

        return node;
    }

    private SemanticNode findOrCreateNode(
            Long userId,
            String label
    ) throws JsonProcessingException {
        SemanticNode existingNode =
                semanticNodeRepo
                        .findSemanticNodeByUserIdAndLabel(
                                userId,
                                label
                        );

        if (existingNode != null) {
            return existingNode;
        }

        String centroid =
                createPhraseCentroidJson(
                        userId,
                        label
                );

        SemanticNode node =
                SemanticNode.builder()
                        .userId(userId)
                        .label(label)
                        .centroid(centroid)
                        .fragmentCount(0)
                        .avgSimilarity(0.0f)
                        .isStable(true)
                        .build();

        return semanticNodeRepo.save(node);
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

    private void upsertDirectEdge(
            SemanticNode sourceNode,
            SemanticNode targetNode,
            long cooccurrenceCount
    ) {
        Optional<SemanticEdge> existingEdge =
                semanticEdgeRepo
                        .findBySourceNode_IdAndTargetNode_Id(
                                sourceNode.getId(),
                                targetNode.getId()
                        );

        if (existingEdge.isPresent()) {
            SemanticEdge edge =
                    existingEdge.get();

            /*
             * Redis contains the complete accumulated count, so assign it.
             * Do not increment again during materialization.
             */
            edge.setCooccurrenceCount(
                    cooccurrenceCount
            );

            /*
             * Invalidate derived values. They can be recomputed after all
             * direct facts have been materialized.
             */
            edge.setNpmi(null);
            edge.setFusedWeight(null);
            edge.setWeightsEpoch(null);

            semanticEdgeRepo.save(edge);
            return;
        }

        SemanticEdge edge =
                SemanticEdge.builder()
                        .sourceNode(sourceNode)
                        .targetNode(targetNode)
                        .cooccurrenceCount(cooccurrenceCount)
                        .sharedKeywordCount(0)
                        .sharedKeywordIdf(0.0f)
                        .learnedConfirmations(0)
                        .npmi(null)
                        .overlapScore(null)
                        .fusedWeight(null)
                        .weightsEpoch(null)
                        .build();

        semanticEdgeRepo.save(edge);
    }

    public float[] getPhraseCentroid(Long userId, String phrase) throws JsonProcessingException {

        String redisKey = "centroids:user:" + userId;

        List<float[]> wordCentroids = new ArrayList<>();

        String redisKeyForGettingKeywords = "phrases:user:" + userId;

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

    private record GraphFormationReport(
            int nodesProcessed,
            int fragmentMappingsCreated,
            int edgesProcessed
    ) {
    }
}