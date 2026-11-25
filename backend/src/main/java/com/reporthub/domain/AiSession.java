package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 智能问数会话（按用户持久化保存） */
@Entity
@Table(name = "ai_sessions", indexes = {
        @Index(name = "idx_ai_sessions_user_updated", columnList = "username,updatedAt")
})
public class AiSession {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
