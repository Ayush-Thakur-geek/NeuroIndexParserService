package com.NeuroIndex.parser.helperClasses;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class KeywordEmbeddingAccumulator {
    private float[] embeddingSum;

    private long occurrenceCount;

    public void incrementCount() {
        occurrenceCount++;
    }
}
