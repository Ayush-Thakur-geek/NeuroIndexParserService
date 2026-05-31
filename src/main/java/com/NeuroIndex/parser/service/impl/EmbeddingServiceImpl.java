package com.NeuroIndex.parser.service.impl;

import com.NeuroIndex.parser.dtos.OllamaEmbeddingRequestDTO;
import com.NeuroIndex.parser.dtos.OllamaEmbeddingResponseDTO;
import com.NeuroIndex.parser.service.EmbeddingService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Service
@Log4j2
public class EmbeddingServiceImpl
        implements EmbeddingService {

    private final RestClient restClient;

    public EmbeddingServiceImpl(
            RestClient.Builder builder
    ) {

        this.restClient = builder
                .baseUrl("http://localhost:11434")
                .build();
    }

    @Override
    public List<List<Float>> createEmbeddings(List<String> paragraphs) {

        OllamaEmbeddingRequestDTO request =
                OllamaEmbeddingRequestDTO.builder()
                        .model("nomic-embed-text")
                        .input(paragraphs)
                        .build();

        OllamaEmbeddingResponseDTO response =
                restClient.post()
                        .uri("/api/embed")
                        .body(request)
                        .retrieve()
                        .body(OllamaEmbeddingResponseDTO.class);

        if (
                response == null ||
                        response.getEmbeddings() == null ||
                        response.getEmbeddings().isEmpty()
        ) {
            throw new RuntimeException(
                    "Failed to generate embeddings"
            );
        }

        List<List<Float>> embeddings =
                response.getEmbeddings();

        return embeddings;
    }
}
