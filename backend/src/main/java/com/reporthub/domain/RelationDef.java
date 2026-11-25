package com.reporthub.domain;

/**
 * 报表多表关联规则定义（代码内置，不再落库存储）。
 */
public class RelationDef {
    private String code;
    private String reportCode;
    private String leftDataset;
    private String rightDataset;
    /** 1-1 | 1-N | N-1 | N-N */
    private String cardinality = "N-1";
    /** left | inner | union */
    private String joinType = "left";
    /** JSON array: [{leftField, rightField}] */
    private String onFields = "[]";
    /** dual_list | child_batch | child_lookup */
    private String fetchMode = "child_batch";
    /** JSON fetch strategy options */
    private String fetchConfig = "{}";
    /** flat | nested — only for 1-N / N-N */
    private String expandMode = "flat";
    private Integer sort = 0;

    public RelationDef() {}

    public RelationDef(String code, String reportCode, String leftDataset, String rightDataset,
                       String cardinality, String joinType, String onFields,
                       String fetchMode, String fetchConfig, String expandMode, Integer sort) {
        this.code = code;
        this.reportCode = reportCode;
        this.leftDataset = leftDataset;
        this.rightDataset = rightDataset;
        this.cardinality = cardinality;
        this.joinType = joinType;
        this.onFields = onFields;
        this.fetchMode = fetchMode;
        this.fetchConfig = fetchConfig;
        this.expandMode = expandMode;
        this.sort = sort;
    }

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
}
