package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.parser.dtos.SemanticSearchResponse;
import com.NeuroIndex.parser.repositories.SemanticFragmentRepo;
import com.NeuroIndex.parser.service.EmbeddingService;
import com.NeuroIndex.parser.service.SemanticSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SemanticSearchServiceImpl implements SemanticSearchService {
    private final EmbeddingService embeddingService;
    private final SemanticFragmentRepo semanticFragmentRepo;
    @Override
    public List<SemanticMessageResult> search(Long userId, String query, int limit) {
        List<String> queries = new ArrayList<>();
        queries.add(query);
        List<List<Float>> queryEmbeddings = embeddingService.createEmbeddings(queries);
        if (queryEmbeddings.isEmpty()) return null;
        List<Float> queryEmbedding = queryEmbeddings.getFirst();

        List<Message> messages =
                semanticFragmentRepo.findBySemanticSearch(
                        userId,
                        queryEmbedding.toString(),
                        limit
                );

        List<List<SemanticUnit>> messageContents = messages.stream()
                .map(message ->
                        message.getContent() == null
                                ? List.<SemanticUnit>of()
                                : message.getContent()
                )
                .toList();


        List<SemanticMessageResult> results = messages.stream()
                .map(message -> new SemanticMessageResult(
                        message.getId(),
                        message.getContent() == null
                                ? List.of()
                                : message.getContent()
                ))
                .toList();
        return results;
    }

    public record SemanticMessageResult(
            Long messageId,
            List<SemanticUnit> content
    ) {}
}

