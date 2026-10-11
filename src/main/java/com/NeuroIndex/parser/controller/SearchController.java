package com.NeuroIndex.parser.controller;

import com.NeuroIndex.parser.annotations.TimeMonitor;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.helperClasses.SearchJobStore;
import com.NeuroIndex.parser.service.SearchService;
import com.NeuroIndex.parser.service.SemanticSearchService;
import com.NeuroIndex.parser.service.impl.SearchServiceImpl;
import com.NeuroIndex.parser.service.impl.SemanticSearchServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/beta/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final SemanticSearchService semanticSearchService;

    @TimeMonitor
    @GetMapping("/fast/{userId}")
    public ResponseEntity<SearchJobStore.SearchTicket> fastSearch(
            @PathVariable("userId") Long userId,
            @RequestParam("query") String query
    ) throws ExecutionException {

        return ResponseEntity.ok(
                searchService.fastSearch(query, userId)
        );
    }

    @TimeMonitor
    @GetMapping("/refinement/{userId}/{searchId}")
    public ResponseEntity<SearchJobStore.SearchTicket> refinement(
            @PathVariable("userId") Long userId,
            @PathVariable("searchId") UUID searchId
    ) {

        return ResponseEntity.ok(
                searchService.getRefinement(userId, searchId)
        );
    }

    @TimeMonitor
    @GetMapping("/semantic/{userId}")
    public ResponseEntity<List<SemanticSearchServiceImpl.SemanticMessageResult>> semanticSearch(
            @PathVariable("userId") Long userId,
            @RequestParam("query") String query,
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(
                semanticSearchService.search(
                        userId,
                        query,
                        limit
                )
        );
    }
}
