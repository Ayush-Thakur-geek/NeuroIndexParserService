package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.LuceneIndexDataDTO;

public interface KeyWordExtractionService {

    public void extractingAndIndexing(LuceneIndexDataDTO luceneIndexDataDTO);

    public void indexing(LuceneIndexDataDTO luceneIndexDataDTO);
}
