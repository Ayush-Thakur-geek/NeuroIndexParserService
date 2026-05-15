package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import com.NeuroIndex.entity.models.LLm;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AffiliatedEmailsRepo extends JpaRepository<AffiliatedEmail, Long> {

    @Query("""
            SELECT a
            FROM AffiliatedEmail a
            WHERE a.llm = :llm
            ORDER BY a.createdDate DESC
            """)
    List<AffiliatedEmail> findLatestEmailByLlm(@Param("llm") LLm llm, Pageable pageable);

    AffiliatedEmail findByUuid(String uuid);
}
