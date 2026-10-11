package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.domainObjects.SemanticUnit;
import com.NeuroIndex.entity.models.Message;
import com.NeuroIndex.entity.models.SemanticFragment;
import jakarta.persistence.QueryHint;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    public List<SemanticFragment> findAllByUserId(Long userId);

    @EntityGraph(attributePaths = "message")
    Optional<SemanticFragment> findByIdAndUserId(
            Long fragmentId,
            Long userId
    );

    interface FragmentMessageContent {
        Long getFragmentId();
        Long getMessageId();
        List<SemanticUnit> getMessageContent();
    }

    @Query("""
        SELECT
            fragment.id AS fragmentId,
            message.id AS messageId,
            message.content AS messageContent
        FROM SemanticFragment fragment
        JOIN fragment.message message
        WHERE fragment.userId = :userId
          AND fragment.id IN :fragmentIds
        """)
    List<FragmentMessageContent> findMessageContent(
            @Param("userId") Long userId,
            @Param("fragmentIds") Collection<Long> fragmentIds
    );

    @Query(
            value = """
                WITH fragment_distances AS MATERIALIZED (
                    SELECT
                        fragment.message_id,
                        fragment.embedding
                            <=> CAST(:queryEmbedding AS vector)
                            AS cosine_distance
                    FROM semantic_fragments fragment
                    WHERE fragment.user_id = :userId
                      AND fragment.embedding IS NOT NULL
                ),
                ranked_messages AS (
                    SELECT
                        message_id,
                        MIN(cosine_distance) AS best_distance
                    FROM fragment_distances
                    GROUP BY message_id
                )
                SELECT message.*
                FROM message
                JOIN ranked_messages ranked
                    ON ranked.message_id = message.id
                ORDER BY
                    ranked.best_distance ASC,
                    message.id ASC
                LIMIT :limit
                """,
            nativeQuery = true
    )
    List<Message> findBySemanticSearch(
            @Param("userId") Long userId,
            @Param("queryEmbedding") String queryEmbedding,
            @Param("limit") int limit
    );

    @QueryHints(
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "5000"
            )
    )
    long countByUserId(Long userId);


    interface FragmentMessageLink {

        Long getFragmentId();

        Long getMessageId();
    }


    @QueryHints(
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "5000"
            )
    )
    @Query("""
        SELECT
            fragment.id AS fragmentId,
            fragment.message.id AS messageId
        FROM SemanticFragment fragment
        WHERE fragment.userId = :userId
          AND fragment.id IN :fragmentIds
        """)
    List<FragmentMessageLink> findSearchMessageLinks(
            @Param("userId") Long userId,
            @Param("fragmentIds") Collection<Long> fragmentIds
    );


    interface SearchMessageContent {

        Long getMessageId();

        List<SemanticUnit> getMessageContent();
    }


    @QueryHints(
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "5000"
            )
    )
    @Query("""
        SELECT
            message.id AS messageId,
            message.content AS messageContent
        FROM Message message
        WHERE message.id IN :messageIds
          AND EXISTS (
              SELECT fragment.id
              FROM SemanticFragment fragment
              WHERE fragment.message.id = message.id
                AND fragment.userId = :userId
          )
        """)
    List<SearchMessageContent> findSearchMessageContents(
            @Param("userId") Long userId,
            @Param("messageIds") Collection<Long> messageIds
    );


}
