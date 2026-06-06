package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;

import java.util.List;

public interface KeyWordExtractionService {

    public void extractingKeyWords(List<LuceneIndexDataDTO> luceneIndexDataDTOs);

    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO);
}
