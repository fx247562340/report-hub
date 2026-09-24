package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_endpoints")
public class ApiEndpointDef {
    @Id
    @Column(length = 64)
    private String code;

    @Column(nullable = false, length = 64)
    private String dataSourceCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 16)
    private String method = "GET";

    @Column(nullable = false, length = 255)
    private String path;

    /** JSON query template with {{placeholders}} */
    @Column(columnDefinition = "text")
    private String queryTemplate = "{}";

    /** JSON body template */
    @Column(columnDefinition = "text")
    private String bodyTemplate;

    /** none | json | form — body 编码方式 */
    @Column(length = 16)
    private String bodyType = "none";

    /** JSON path to list in response, e.g. data.records */
    @Column(length = 128)
    private String listPath = "data";

    @Column(length = 128)
    private String totalPath;

    /** none | page */
    @Column(length = 16)
    private String pagination = "none";

    @Column(columnDefinition = "text")
    private String headersTemplate = "{}";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getDataSourceCode() { return dataSourceCode; }
    public void setDataSourceCode(String dataSourceCode) { this.dataSourceCode = dataSourceCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getQueryTemplate() { return queryTemplate; }
    public void setQueryTemplate(String queryTemplate) { this.queryTemplate = queryTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public String getBodyType() { return bodyType; }
    public void setBodyType(String bodyType) { this.bodyType = bodyType; }
    public String getListPath() { return listPath; }
    public void setListPath(String listPath) { this.listPath = listPath; }
    public String getTotalPath() { return totalPath; }
    public void setTotalPath(String totalPath) { this.totalPath = totalPath; }
    public String getPagination() { return pagination; }
    public void setPagination(String pagination) { this.pagination = pagination; }
    public String getHeadersTemplate() { return headersTemplate; }
    public void setHeadersTemplate(String headersTemplate) { this.headersTemplate = headersTemplate; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
