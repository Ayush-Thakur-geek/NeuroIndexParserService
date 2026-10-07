package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;

import java.util.List;
import java.util.concurrent.ExecutionException;

public interface SearchService {
    public List<SearchResponseDTO> fastSearch(String query, Long userId) throws ExecutionException, InterruptedException;
    public Message extractMessage(SearchResponseDTO searchResponseDTO, Long userId);
}
