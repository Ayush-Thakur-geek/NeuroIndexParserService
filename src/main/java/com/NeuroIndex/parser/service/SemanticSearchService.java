package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.SemanticSearchResponse;
import com.NeuroIndex.parser.service.impl.SemanticSearchServiceImpl;

import java.util.List;

public interface SemanticSearchService {
    public List<SemanticSearchServiceImpl.SemanticMessageResult> search(Long userId, String query, int limit);
}
