package com.NeuroIndex.parser.dtos;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class NounPhraseExtractionDTO {
    private Long userId;
    private Long llmId;
    private Long affiliatedEmailId;
    private Long conversationId;
    private Long messageId;
    private Long semanticFragmentId;
    private String text;
}
