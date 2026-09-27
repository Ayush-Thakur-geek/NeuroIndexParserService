package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.dtos.LuceneKeywordExtractDTO;
import com.NeuroIndex.parser.dtos.NounPhraseExtractionDTO;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

public interface KeyWordExtractionService {

    public void extractingKeyWords(List<LuceneKeywordExtractDTO> luceneIndexDataDTOs);

    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO);

    public float[] getPhraseCentroid(Long userId, String phrase) throws JsonProcessingException;
}
