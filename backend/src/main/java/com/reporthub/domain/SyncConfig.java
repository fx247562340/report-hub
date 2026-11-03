package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 上游原始数据同步配置（按数据集，不建业务表） */
@Entity
@Table(name = "sync_configs")
public class SyncConfig {
    @Id
    @Column(length = 64)
    private String datasetCode;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** list：分页拉列表接口 */
    @Column(nullable = false, length = 16)
    private String mode = "list";

    @Column(nullable = false)
    private Integer pageSize = 200;

    @Column(nullable = false)
    private Integer maxPages = 500;

    @Column(nullable = false, columnDefinition = "text")
    private String notes = "";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getDatasetCode() { return datasetCode; }
    public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Integer getPageSize() { return pageSize; }
    public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
    public Integer getMaxPages() { return maxPages; }
    public void setMaxPages(Integer maxPages) { this.maxPages = maxPages; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
