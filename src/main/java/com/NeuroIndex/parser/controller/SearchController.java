package com.NeuroIndex.parser.controller;

import com.NeuroIndex.parser.dtos.SearchResponseDTO;
import com.NeuroIndex.parser.service.SearchService;
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

    // GET /beta/v1/search/fast/1?query=redis%20connection
    @GetMapping("/fast/{userId}")
    public ResponseEntity<List<Long>> fastSearch(
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

        List<SearchResponseDTO> results =
                searchService.fastSearch(
                        query.trim(),
                        userId
                );

        List<Long> fragIds = results.stream()
                .map(SearchResponseDTO::getFragmentId).toList();

        return ResponseEntity.ok(fragIds);
    }
}
