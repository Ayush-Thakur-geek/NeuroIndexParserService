package com.NeuroIndex.parser.service;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.Conversation;
import com.NeuroIndex.parser.dtos.ClaudeConversationJsonDTO;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ClaudeIngestionService {

    public List<Conversation> ingestClaudeConversations(AffiliatedEmail affiliatedEmail, List<ClaudeConversationJsonDTO> conversations) throws IOException;
    public CompletableFuture<Void> keywordExtractionInitiation(AffiliatedEmail affiliatedEmail, List<Conversation> conversations);
}
