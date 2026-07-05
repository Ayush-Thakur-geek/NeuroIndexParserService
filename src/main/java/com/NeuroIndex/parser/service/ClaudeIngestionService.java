package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.Conversation;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;

import java.io.IOException;
import java.util.List;

public interface ClaudeIngestionService {

    public List<Conversation> ingestClaudeConversations(AffiliatedEmail affiliatedEmail, List<ClaudeConversationJsonDTO> conversations) throws IOException;
    public void keywordExtractionInitiation(AffiliatedEmail affiliatedEmail, List<Conversation> conversations);
}
