package com.NeuroIndex.parser.repositories;

import com.NeuroIndex.entity.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepo extends JpaRepository<User, Long> {
    User getUserByEmail(String email);
}
