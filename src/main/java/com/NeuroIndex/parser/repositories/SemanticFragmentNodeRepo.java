package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.SemanticFragmentNode;
import com.NeuroIndex.entity.models.SemanticNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SemanticFragmentNodeRepo extends JpaRepository<SemanticFragmentNode, Long> {
    boolean existsByFragment_IdAndNode_Id(Long id, Long id1);

    List<SemanticFragmentNode> findAllByFragment_IdIn(List<Long> fragmentIds);
}
