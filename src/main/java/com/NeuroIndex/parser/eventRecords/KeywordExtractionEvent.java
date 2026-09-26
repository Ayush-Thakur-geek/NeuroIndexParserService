package com.NeuroIndex.parser.eventRecords;

import com.NeuroIndex.parser.dtos.LuceneKeywordExtractDTO;

import java.util.List;

public record KeywordExtractionEvent(List<LuceneKeywordExtractDTO> tasks) {
}
