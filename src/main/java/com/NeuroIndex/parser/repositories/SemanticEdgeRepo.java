package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticEdge;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SemanticEdgeRepo extends JpaRepository<SemanticEdge, Long> {
    Optional<SemanticEdge> findBySourceNode_IdAndTargetNode_Id(Long id, Long id1);
    @EntityGraph(attributePaths = {"sourceNode", "targetNode"})
    List<SemanticEdge> findAllBySourceNode_UserId(Long userId);

    @Query("""
        SELECT edge
        FROM SemanticEdge edge
        WHERE edge.sourceNode.id = :nodeId
           OR edge.targetNode.id = :nodeId
        """)
    List<SemanticEdge> findAllConnectedToNode(
            @Param("nodeId") Long nodeId
    );

    interface SearchEdgeStats {

        Long getEdgeId();

        Long getSourceNodeId();

        Long getTargetNodeId();

        Integer getSourceFragmentCount();

        Integer getTargetFragmentCount();

        Long getCooccurrenceCount();
    }


    @QueryHints(
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "5000"
            )
    )
    @Query("""
        SELECT
            edge.id AS edgeId,
            source.id AS sourceNodeId,
            target.id AS targetNodeId,
            source.fragmentCount AS sourceFragmentCount,
            target.fragmentCount AS targetFragmentCount,
            edge.cooccurrenceCount AS cooccurrenceCount
        FROM SemanticEdge edge
        JOIN edge.sourceNode source
        JOIN edge.targetNode target
        WHERE source.userId = :userId
          AND target.userId = :userId
          AND edge.cooccurrenceCount > 0
          AND (
              source.id IN :nodeIds
              OR target.id IN :nodeIds
          )
        """)
    List<SearchEdgeStats> findSearchFrontierEdges(
            @Param("userId") Long userId,
            @Param("nodeIds") Collection<Long> nodeIds
    );
    @QueryHints(
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "5000"
            )
    )
    @Query("""
        SELECT edge
        FROM SemanticEdge edge
        JOIN FETCH edge.sourceNode source
        JOIN FETCH edge.targetNode target
        WHERE source.userId = :userId
          AND target.userId = :userId
          AND edge.cooccurrenceCount > 0
          AND (
              source.id IN :nodeIds
              OR target.id IN :nodeIds
          )
        """)
    List<SemanticEdge> findAllConnectedToNodes(
            @Param("userId") Long userId,
            @Param("nodeIds") Collection<Long> nodeIds
    );
}
