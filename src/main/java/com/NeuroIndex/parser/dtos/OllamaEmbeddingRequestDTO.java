package com.NeuroIndex.parser.dtos;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OllamaEmbeddingRequestDTO {
    String model;
    List<String> input;
}
