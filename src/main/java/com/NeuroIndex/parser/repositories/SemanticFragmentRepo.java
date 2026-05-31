package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticFragment;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SemanticFragmentRepo extends JpaRepository<SemanticFragment, Long> {

    @Modifying
    @Transactional
    @Query(value = """
    INSERT INTO semantic_fragments
    (
        message_id,
        text,
        embedding,
        hash,
        fragment_order,
        confidence_score,
        created_date,
        last_modified_date
    )
    VALUES
    (
        :messageId,
        :text,
        CAST(:embedding AS vector),
        :hash,
        :fragmentOrder,
        :confidenceScore,
        NOW(),
        NOW()
    )
    """, nativeQuery = true)
    void insertFragment(
            @Param("messageId") Long messageId,
            @Param("text") String text,
            @Param("embedding") String embedding,
            @Param("hash") String hash,
            @Param("fragmentOrder") Integer fragmentOrder,
            @Param("confidenceScore") Float confidenceScore
    );
}
