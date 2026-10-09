package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticFragmentNode;
import com.NeuroIndex.entity.models.SemanticNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SemanticFragmentNodeRepo extends JpaRepository<SemanticFragmentNode, Long> {
    boolean existsByFragment_IdAndNode_Id(Long id, Long id1);

    List<SemanticFragmentNode> findAllByFragment_IdIn(List<Long> fragmentIds);

    @Query("""
        SELECT mapping
        FROM SemanticFragmentNode mapping
        JOIN FETCH mapping.node node
        WHERE mapping.fragment.userId = :userId
          AND node.userId = :userId
          AND mapping.fragment.id IN :fragmentIds
        """)
    List<SemanticFragmentNode> findSeedMappings(
            @Param("userId") Long userId,
            @Param("fragmentIds") Collection<Long> fragmentIds
    );

    interface GraphPosting {
        Long getFragmentId();
        Long getMappingId();
    }

    @Query("""
        SELECT
            mapping.fragment.id AS fragmentId,
            mapping.id AS mappingId
        FROM SemanticFragmentNode mapping
        WHERE mapping.node.userId = :userId
          AND mapping.fragment.userId = :userId
          AND mapping.node.id IN :nodeIds
        ORDER BY mapping.fragment.id, mapping.id
        """)
    List<GraphPosting> findGraphPostings(
            @Param("userId") Long userId,
            @Param("nodeIds") Collection<Long> nodeIds
    );
}
