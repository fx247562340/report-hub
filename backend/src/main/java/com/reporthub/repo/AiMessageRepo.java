package com.reporthub.repo;

import com.reporthub.domain.AiMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiMessageRepo extends JpaRepository<AiMessage, Long> {
    List<AiMessage> findBySessionIdOrderByCreatedAtAscIdAsc(String sessionId);
    void deleteBySessionId(String sessionId);
}
