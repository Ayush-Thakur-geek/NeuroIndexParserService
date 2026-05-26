package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public interface MessageRepo extends JpaRepository<Message, Long> {

    boolean existsByHash(String messageHash);

    @Query("""
        select m.hash
        from Message m
    """)
    Set<String> findAllHashes();
}
