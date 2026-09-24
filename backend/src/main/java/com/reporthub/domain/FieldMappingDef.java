package com.reporthub.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "field_mappings", uniqueConstraints = @UniqueConstraint(columnNames = {"datasetCode", "targetField"}))
public class FieldMappingDef {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String datasetCode;

    /** source path in upstream row, e.g. id or orderNumber */
    @Column(nullable = false, length = 255)
    private String sourcePath;

    @Column(nullable = false, length = 64)
    private String targetField;

    /** string | number | date | bool */
    @Column(nullable = false, length = 16)
    private String dataType = "string";

    /** optional: trim | upper | lower */
    @Column(length = 32)
    private String transform;

    @Column(length = 64)
    private String format;

    private Integer sort = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDatasetCode() { return datasetCode; }
    public void setDatasetCode(String datasetCode) { this.datasetCode = datasetCode; }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getTargetField() { return targetField; }
    public void setTargetField(String targetField) { this.targetField = targetField; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getTransform() { return transform; }
    public void setTransform(String transform) { this.transform = transform; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
}
