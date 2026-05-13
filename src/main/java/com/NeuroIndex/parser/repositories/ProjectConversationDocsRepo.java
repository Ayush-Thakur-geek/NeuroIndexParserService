package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.ProjectConversationDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectConversationDocsRepo extends JpaRepository<ProjectConversationDoc,Long> {
}
