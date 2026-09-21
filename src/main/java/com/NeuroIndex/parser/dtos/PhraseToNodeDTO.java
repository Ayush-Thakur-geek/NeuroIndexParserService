package com.NeuroIndex.parser.dtos;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PhraseToNodeDTO {
    private long userId;
    private String nounPhrase;
    private String hash;
}
