package com.NeuroIndex.parser.controller;

import com.NeuroIndex.parser.dtos.ExtractionFileDTO;
import com.NeuroIndex.parser.service.ExportFileIngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/beta/v1/extract-file-ingestion")
public class ExtractFileIngestionController {

    private final ExportFileIngestionService exportFileIngestionService;

    public ExtractFileIngestionController(
            ExportFileIngestionService exportFileIngestionService
    ) {
        this.exportFileIngestionService = exportFileIngestionService;
    }

    @PostMapping(
            value = "/user-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<String> userFileIngestion(
            @ModelAttribute ExtractionFileDTO extractionFileDTO
    ) {

        try {

            exportFileIngestionService.extractUserInfo(extractionFileDTO);

            return ResponseEntity.ok("OK");

        } catch (IOException e) {

            throw new RuntimeException(e);
        }
    }

    @PostMapping(
            value = "/conversation-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<String> conversationFileIngestion(
            @ModelAttribute ExtractionFileDTO extractionFileDTO
    ) {
        try {

            exportFileIngestionService.parseConversationExportFile(extractionFileDTO);
            return ResponseEntity.ok("OK");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}