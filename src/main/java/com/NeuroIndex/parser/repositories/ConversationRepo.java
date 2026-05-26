package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

public interface ConversationRepo
        extends JpaRepository<Conversation, Long> {

    boolean existsByHash(String hash);

    @Query("""
        select c.hash
        from Conversation c
    """)
    Set<String> findAllHashes();
}
