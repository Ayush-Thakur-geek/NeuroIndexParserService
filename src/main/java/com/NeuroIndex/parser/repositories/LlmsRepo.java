package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.LLm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmsRepo extends JpaRepository<LLm, Long> {
}
