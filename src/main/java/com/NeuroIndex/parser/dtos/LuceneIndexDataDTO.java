package com.NeuroIndex.parser.dtos;

import com.NeuroIndex.entity.models.SemanticFragment;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LuceneIndexDataDTO {
    private Long userId;
    private String email;
    private SemanticFragment semanticFragment;
}
