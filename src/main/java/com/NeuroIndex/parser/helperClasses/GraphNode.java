package com.NeuroIndex.parser.helperClasses;

import lombok.*;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GraphNode {
    private Long id;
    private String phrase;
    private List<String> aliases;
    private List<Long> connectedNodes;
}
