package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticFragment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SemanticFragmentRepo extends JpaRepository<SemanticFragment, Long> {
}
