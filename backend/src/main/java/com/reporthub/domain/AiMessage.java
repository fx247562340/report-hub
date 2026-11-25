package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 智能问数消息记录（含调用工具轨迹与报表下钻链接，重开历史会话可完整还原） */
@Entity
@Table(name = "ai_messages", indexes = {
        @Index(name = "idx_ai_messages_session_created", columnList = "sessionId,createdAt")
})
public class AiMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String sessionId;

    /** user | assistant */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content = "";

    /** JSON array: [{tool, label, summary}] */
    @Column(columnDefinition = "text")
    private String toolsJson = "[]";

    /** JSON array: [{label, to}] */
    @Column(columnDefinition = "text")
    private String linksJson = "[]";

    private Integer durationMs;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
    public String getLinksJson() { return linksJson; }
    public void setLinksJson(String linksJson) { this.linksJson = linksJson; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
