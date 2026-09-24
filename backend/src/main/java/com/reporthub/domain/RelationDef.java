package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "relations")
public class RelationDef {
    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 64)
    private String reportCode;

    @Column(nullable = false, length = 64)
    private String leftDataset;

    @Column(nullable = false, length = 64)
    private String rightDataset;

    /** 1-1 | 1-N | N-1 | N-N */
    @Column(nullable = false, length = 8)
    private String cardinality = "N-1";

    /** left | inner */
    @Column(nullable = false, length = 8)
    private String joinType = "left";

    /** JSON array: [{leftField, rightField}] */
    @Column(nullable = false, columnDefinition = "text")
    private String onFields = "[]";

    /** dual_list | child_batch | child_lookup */
    @Column(nullable = false, length = 16)
    private String fetchMode = "child_batch";

    /** JSON fetch strategy options */
    @Column(columnDefinition = "text")
    private String fetchConfig = "{}";

    /** flat | nested — only for 1-N / N-N */
    @Column(nullable = false, length = 8)
    private String expandMode = "flat";

    private Integer sort = 0;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getReportCode() { return reportCode; }
    public void setReportCode(String reportCode) { this.reportCode = reportCode; }
    public String getLeftDataset() { return leftDataset; }
    public void setLeftDataset(String leftDataset) { this.leftDataset = leftDataset; }
    public String getRightDataset() { return rightDataset; }
    public void setRightDataset(String rightDataset) { this.rightDataset = rightDataset; }
    public String getCardinality() { return cardinality; }
    public void setCardinality(String cardinality) { this.cardinality = cardinality; }
    public String getJoinType() { return joinType; }
    public void setJoinType(String joinType) { this.joinType = joinType; }
    public String getOnFields() { return onFields; }
    public void setOnFields(String onFields) { this.onFields = onFields; }
    public String getFetchMode() { return fetchMode; }
    public void setFetchMode(String fetchMode) { this.fetchMode = fetchMode; }
    public String getFetchConfig() { return fetchConfig; }
    public void setFetchConfig(String fetchConfig) { this.fetchConfig = fetchConfig; }
    public String getExpandMode() { return expandMode; }
    public void setExpandMode(String expandMode) { this.expandMode = expandMode; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
