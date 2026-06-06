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
    private Long llmId;
    private Long affiliatedEmailId;
    private Long conversationId;
    private Long messageId;
    private Long semanticFragmentId;
    private String text;
}
