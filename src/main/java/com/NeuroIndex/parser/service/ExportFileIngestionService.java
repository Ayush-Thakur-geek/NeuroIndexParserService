package com.NeuroIndex.parser.service;

import com.NeuroIndex.parser.dtos.ExtractionFileDTO;

import java.io.IOException;

public interface ExportFileIngestionService {
    public void extractUserInfo(ExtractionFileDTO extractionFileDTO) throws IOException;
    public void parseConversationExportFile(ExtractionFileDTO extractionFileDTO) throws IOException;
}
