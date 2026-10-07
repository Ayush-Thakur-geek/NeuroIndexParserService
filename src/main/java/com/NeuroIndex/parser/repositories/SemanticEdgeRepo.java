package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticEdge;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SemanticEdgeRepo extends JpaRepository<SemanticEdge, Long> {
    Optional<SemanticEdge> findBySourceNode_IdAndTargetNode_Id(Long id, Long id1);
    @EntityGraph(attributePaths = {"sourceNode", "targetNode"})
    List<SemanticEdge> findAllBySourceNode_UserId(Long userId);
}
