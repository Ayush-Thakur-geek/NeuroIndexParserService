package com.NeuroIndex.parser.dtos;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PhraseToNodeDTO {

    @EqualsAndHashCode.Include
    private Long userId;

    @EqualsAndHashCode.Include
    private String nounPhrase;

    private String hash;
}