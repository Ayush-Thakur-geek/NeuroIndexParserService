package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.helperClasses.SearchJobStore;
import com.NeuroIndex.parser.service.impl.SearchServiceImpl;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public interface SearchService {

    SearchJobStore.SearchTicket fastSearch(
            String query,
            Long userId
    ) throws ExecutionException;

    SearchJobStore.SearchTicket getRefinement(
            Long userId,
            UUID searchId
    );

    List<SearchServiceImpl.MessageSearchResponse> extractMessage(
            Long userId,
            List<SearchServiceImpl.LexicalHit> lexicalHits,
            List<SearchServiceImpl.GraphHit> graphHits
    );
}