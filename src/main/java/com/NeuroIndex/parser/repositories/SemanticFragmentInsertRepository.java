package com.NeuroIndex.parser.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class SemanticFragmentInsertRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Transactional
    public Long insertFragment(
            Long userId,
            Long messageId,
            String text,
            String embedding,
            String hash,
            Integer fragmentOrder,
            Float confidenceScore
    ) {
        String sql = """
                INSERT INTO semantic_fragments
                (
                    message_id,
                    user_id,
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
                    :userId,
                    :text,
                    CAST(:embedding AS vector),
                    :hash,
                    :fragmentOrder,
                    :confidenceScore,
                    NOW(),
                    NOW()
                )
                RETURNING id
                """;

        MapSqlParameterSource parameters =
                new MapSqlParameterSource()
                        .addValue("messageId", messageId)
                        .addValue("userId", userId)
                        .addValue("text", text)
                        .addValue("embedding", embedding)
                        .addValue("hash", hash)
                        .addValue(
                                "fragmentOrder",
                                fragmentOrder
                        )
                        .addValue(
                                "confidenceScore",
                                confidenceScore
                        );

        Long generatedId =
                jdbcTemplate.queryForObject(
                        sql,
                        parameters,
                        Long.class
                );

        if (generatedId == null) {
            throw new IllegalStateException(
                    "Database did not return an ID for semantic fragment"
            );
        }

        return generatedId;
    }
}
