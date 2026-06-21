package com.NeuroIndex.parser.dtos;

import lombok.*;

import java.util.List;

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
    List<String> nounPhrases;
}
