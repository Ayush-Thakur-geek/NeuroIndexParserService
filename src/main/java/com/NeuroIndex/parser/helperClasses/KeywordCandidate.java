package com.NeuroIndex.parser.helperClasses;

import lombok.*;

@Builder
public record KeywordCandidate(
        String keyword,
        double score
) {}
