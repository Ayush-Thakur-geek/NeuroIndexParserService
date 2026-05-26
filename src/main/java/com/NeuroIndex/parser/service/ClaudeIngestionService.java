package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;

import java.io.IOException;
import java.util.List;

public interface ClaudeIngestionService {

    public void ingestClaudeConversations(AffiliatedEmail affiliatedEmail, List<ClaudeConversationJsonDTO> conversations) throws IOException;
}
