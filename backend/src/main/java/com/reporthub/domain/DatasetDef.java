package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "datasets")
public class DatasetDef {
    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 64)
    private String endpointCode;

    /** list | detail — how this dataset is typically fetched */
    @Column(nullable = false, length = 16)
    private String fetchHint = "list";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEndpointCode() { return endpointCode; }
    public void setEndpointCode(String endpointCode) { this.endpointCode = endpointCode; }
    public String getFetchHint() { return fetchHint; }
    public void setFetchHint(String fetchHint) { this.fetchHint = fetchHint; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
