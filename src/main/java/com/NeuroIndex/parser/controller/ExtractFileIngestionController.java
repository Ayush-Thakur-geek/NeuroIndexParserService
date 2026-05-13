package com.NeuroIndex.parser.controller;

import com.NeuroIndex.parser.dtos.ExtractionFileDTO;
import com.NeuroIndex.parser.service.UserFileIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/beta/v1/extract-file-ingestion")
public class ExtractFileIngestionController {

    private final UserFileIngestionService userFileIngestionService;

    public ExtractFileIngestionController(
            UserFileIngestionService userFileIngestionService
    ) {
        this.userFileIngestionService = userFileIngestionService;
    }

    @PostMapping(
            value = "/user-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> userFileIngestion(
            @ModelAttribute ExtractionFileDTO extractionFileDTO
    ) {

        try {

            userFileIngestionService.extractInfo(extractionFileDTO);

            return ResponseEntity.ok().build();

        } catch (IOException e) {

            throw new RuntimeException(e);
        }
    }
}