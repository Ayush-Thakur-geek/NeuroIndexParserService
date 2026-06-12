package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;
import com.NeuroIndex.parser.dtos.LuceneKeywordExtractDTO;

import java.util.List;

public interface KeyWordExtractionService {

    public void extractingKeyWords(List<LuceneKeywordExtractDTO> luceneIndexDataDTOs);

    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO);
}
