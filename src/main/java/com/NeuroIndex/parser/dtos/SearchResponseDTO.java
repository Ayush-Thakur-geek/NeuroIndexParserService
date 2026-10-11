package com.NeuroIndex.parser.dtos;

import com.NeuroIndex.parser.service.impl.SearchServiceImpl;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDTO {
    private Long userId;
    private List<SearchServiceImpl.GraphHit> graphHits;
    private List<SearchServiceImpl.LexicalHit> lexicalHits;
}
