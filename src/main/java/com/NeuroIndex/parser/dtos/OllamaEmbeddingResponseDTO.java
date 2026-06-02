package com.NeuroIndex.parser.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OllamaEmbeddingResponseDTO {
    @JsonProperty("model")
    private String model;
    @JsonProperty("embeddings")
    private List<List<Float>> embeddings;
    @JsonProperty("total_duration")
    private long totalDuration;
    @JsonProperty("load_duration")
    private long loadDuration;
    @JsonProperty("prompt_eval_count")
    private long promptEvalCount;
}
