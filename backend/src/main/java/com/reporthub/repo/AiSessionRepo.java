package com.reporthub.repo;

import com.reporthub.domain.AiSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiSessionRepo extends JpaRepository<AiSession, String> {
    List<AiSession> findTop50ByUsernameOrderByUpdatedAtDesc(String username);
}
