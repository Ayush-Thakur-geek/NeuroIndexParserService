package com.NeuroIndex.parser.controller;

import com.NeuroIndex.parser.annotations.TimeMonitor;
import com.NeuroIndex.parser.dtos.SearchResponseDTO;
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
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/beta/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final SemanticSearchService semanticSearchService;

    // GET /beta/v1/search/fast/1?query=redis%20connection
    @TimeMonitor
    @GetMapping("/fast/{userId}")
    public ResponseEntity<List<SearchServiceImpl.MessageSearchResponse>> fastSearch(
            @PathVariable("userId") Long userId,
            @RequestParam("query") String query
    ) throws ExecutionException, InterruptedException {

        if (userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User ID must be positive"
            );
        }

        if (query.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Search query must not be blank"
            );
        }

        SearchResponseDTO result =
                searchService.fastSearch(
                        query.trim(),
                        userId
                );

        List<SearchServiceImpl.GraphHit> graphHits = result.getGraphHits();
        List<SearchServiceImpl.LexicalHit> lexicalHits = result.getLexicalHits();

        List<SearchServiceImpl.MessageSearchResponse> searchResults = searchService.extractMessage(userId, lexicalHits, graphHits);


        return ResponseEntity.ok(searchResults);
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
