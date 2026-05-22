package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepo extends JpaRepository<Message, Long> {

    boolean existsByHash(String messageHash);
}
