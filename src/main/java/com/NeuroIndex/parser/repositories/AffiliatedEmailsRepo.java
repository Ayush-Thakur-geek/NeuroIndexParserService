package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.AffiliatedEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AffiliatedEmailsRepo extends JpaRepository<AffiliatedEmail, Long> {
}
