package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.ExtractionFileDTO;

import java.io.IOException;

public interface UserFileIngestionService {
    public void extractInfo(ExtractionFileDTO extractionFileDTO) throws IOException;
}
