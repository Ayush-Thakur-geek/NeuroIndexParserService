package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.service.impl.SearchServiceImpl;

import java.util.List;
import java.util.concurrent.ExecutionException;

public interface SearchService {
    public SearchResponseDTO fastSearch(String query, Long userId) throws ExecutionException, InterruptedException;
    public List<SearchServiceImpl.MessageSearchResponse> extractMessage(Long userId, List<SearchServiceImpl.LexicalHit> rankedHits, List<SearchServiceImpl.GraphHit> graphHits);
}
