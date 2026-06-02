package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SemanticNodeRepo extends JpaRepository<SemanticNode, Long> {
}
