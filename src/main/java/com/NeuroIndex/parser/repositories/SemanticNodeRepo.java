package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SemanticNodeRepo extends JpaRepository<SemanticNode, Long> {

    @Query(value = """
        SELECT *
        FROM semantic_node
        WHERE user_id = :userId
          AND label = :phrase
        """, nativeQuery = true)
    SemanticNode findSemanticNodeByUserIdAndLabel(
            @Param("userId") Long userId,
            @Param("phrase") String phrase
    );

    List<SemanticNode> findAllByUserId(Long userId);
}
