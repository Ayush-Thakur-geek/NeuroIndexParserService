package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.PhraseToNodeDTO;
import com.NeuroIndex.parser.service.GraphFormationService;
import com.NeuroIndex.parser.service.HashingSHA256Service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.UnifiedJedis;

import java.util.*;

@Service
@RequiredArgsConstructor
public class GraphFormationServiceImpl implements GraphFormationService {

    private final UnifiedJedis jedis;
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

        List<PhraseToNodeDTO> phrases = phraseDtos.stream()
                .filter(Objects::nonNull)
                .filter(dto -> dto.getNounPhrase() != null)
                .filter(dto -> !dto.getNounPhrase().isBlank())
                .filter(dto -> dto.getHash() != null)
                .distinct()
                .toList();

        if (phrases.isEmpty()) {
            return;
        }

        String fragmentNodesKey =
                "graph:fragment:nodes:"
                        + userId
                        + ":"
                        + semanticFragmentId;

        String nodeLabelsKey =
                "graph:node:labels:"
                        + userId;

        String directEdgeCountsKey =
                "graph:direct:counts:"
                        + userId;

        String keywordPhraseKeyPrefix =
                "graph:keyword:phrases:"
                        + userId
                        + ":";

        /*
         * 1. Record which phrase nodes belong to this fragment.
         */
        for (PhraseToNodeDTO phrase : phrases) {
            jedis.sadd(
                    fragmentNodesKey,
                    phrase.getHash()
            );

            /*
             * Hash is only the Redis lookup key.
             * The normalized phrase remains the authoritative label.
             */
            jedis.hset(
                    nodeLabelsKey,
                    phrase.getHash(),
                    phrase.getNounPhrase()
            );
        }

        /*
         * 2. Record direct phrase relationships.
         *
         * Every pair of phrases in the same fragment is directly related.
         */
        for (int i = 0; i < phrases.size(); i++) {
            for (int j = i + 1; j < phrases.size(); j++) {
                String sourceHash = phrases.get(i).getHash();
                String targetHash = phrases.get(j).getHash();

                String pair = canonicalPair(sourceHash, targetHash);

                jedis.hincrBy(
                        directEdgeCountsKey,
                        pair,
                        1
                );
            }
        }

        /*
         * 3. Record keyword → phrase mappings.
         *
         * This is useful for later indirect/alias expansion.
         */
        if (keywords != null && !keywords.isEmpty()) {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }

                String normalizedKeyword =
                        normalizeKeyword(keyword);

                if (normalizedKeyword.isBlank()) {
                    continue;
                }

                String keywordPhrasesKey =
                        keywordPhraseKeyPrefix + normalizedKeyword;

                for (PhraseToNodeDTO phrase : phrases) {
                    jedis.sadd(
                            keywordPhrasesKey,
                            phrase.getHash()
                    );
                }
            }
        }
    }

    private String canonicalPair(String first, String second) {
        if (first.compareTo(second) < 0) {
            return first + "|" + second;
        }

        return second + "|" + first;
    }

    private String normalizeKeyword(String keyword) {
        return keyword
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
