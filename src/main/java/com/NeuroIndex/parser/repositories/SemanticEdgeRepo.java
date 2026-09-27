package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SemanticEdgeRepo extends JpaRepository<SemanticEdge, Long> {
    Optional<SemanticEdge> findBySourceNode_IdAndTargetNode_Id(Long id, Long id1);
}
