package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reports")
public class ReportDef {
    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, length = 64)
    private String rootDataset;

    /** JSON: [{key,label,from,format,sort}] */
    @Column(nullable = false, columnDefinition = "text")
    private String fieldsJson = "[]";

    /** JSON: [{key,label,op,bind,type}] */
    @Column(columnDefinition = "text")
    private String filtersJson = "[]";

    /** JSON: [{field,dir}] */
    @Column(columnDefinition = "text")
    private String orderByJson = "[]";

    /** JSON calc/condition columns */
    @Column(columnDefinition = "text")
    private String calcsJson = "[]";

    /** JSON pagination options */
    @Column(columnDefinition = "text")
    private String paginationJson = "{}";

    /** JSON export options */
    @Column(columnDefinition = "text")
    private String exportConfig = "{}";

    private boolean enabled = true;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRootDataset() { return rootDataset; }
    public void setRootDataset(String rootDataset) { this.rootDataset = rootDataset; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public String getOrderByJson() { return orderByJson; }
    public void setOrderByJson(String orderByJson) { this.orderByJson = orderByJson; }
    public String getCalcsJson() { return calcsJson; }
    public void setCalcsJson(String calcsJson) { this.calcsJson = calcsJson; }
    public String getPaginationJson() { return paginationJson; }
    public void setPaginationJson(String paginationJson) { this.paginationJson = paginationJson; }
    public String getExportConfig() { return exportConfig; }
    public void setExportConfig(String exportConfig) { this.exportConfig = exportConfig; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
