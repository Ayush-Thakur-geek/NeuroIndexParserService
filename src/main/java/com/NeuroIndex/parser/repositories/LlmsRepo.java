package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.LLm;
import com.NeuroIndex.entity.models.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LlmsRepo extends JpaRepository<LLm, Long> {
    @Query("""
            SELECT l
            FROM LLm l
            WHERE l.user = :user
            ORDER BY l.createdDate DESC
            """)
    List<LLm> findLatestLlmByUser(@Param("user") User user, Pageable pageable);

    @Query("""
        SELECT l
        FROM LLm l
        WHERE :affiliatedEmail MEMBER OF l.affiliatedEmails
        """)
    LLm findByAffiliatedEmailObject(
            @Param("affiliatedEmail")
            AffiliatedEmail affiliatedEmail
    );
}
