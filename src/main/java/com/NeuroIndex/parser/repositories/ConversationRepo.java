package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepo extends JpaRepository<Conversation, Long> {
    boolean existsByHash(String hash);
}
