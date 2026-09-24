package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "data_sources")
public class DataSourceDef {
    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 255)
    private String baseUrl;

    /** none | basic | bearer | api_key */
    @Column(nullable = false, length = 16)
    private String authType = "none";

    /** JSON: credentials / header names */
    @Column(columnDefinition = "text")
    private String authConfig = "{}";

    @Column(length = 32)
    private String env = "prod";

    private Integer timeoutMs = 15000;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getAuthConfig() { return authConfig; }
    public void setAuthConfig(String authConfig) { this.authConfig = authConfig; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
