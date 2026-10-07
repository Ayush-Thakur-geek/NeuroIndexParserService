package com.NeuroIndex.parser.dtos;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDTO {
    private Long userId;
    private Long fragmentId;
}
