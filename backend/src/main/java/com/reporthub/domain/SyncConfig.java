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

    /** related 模式：上游父数据集（本地已同步） */
    @Column(length = 64)
    private String parentDataset;

    /** related 模式：父 payload 里取键的 source_path */
    @Column(length = 255)
    private String keyPath;

    /** keys 模式：枚举上游表全部键的 SQL（QueryInfoBySql） */
    @Column(columnDefinition = "text")
    private String keysSql;

    /** keys 模式：执行 keys_sql 的 SQL 接口编码 */
    @Column(length = 64)
    private String keysEndpoint;

    /** 增量取数的请求合并规则（queryMerge/bodyMerge/filtersAppend，值可含 {{sinceDate}}） */
    @Column(columnDefinition = "text")
    private String incrementalFilter;

    /** manual | interval | daily | weekly */
    @Column(nullable = false, length = 16)
    private String scheduleType = "manual";

    /** daily/weekly 的 HH:mm */
    @Column(length = 8)
    private String scheduleTime;

    /** weekly: 1=周一 .. 7=周日 */
    private Integer scheduleWeekday;

    /** interval：每隔 N 分钟 */
    private Integer scheduleIntervalMin;

    private Instant lastScheduleRun;

    private Instant nextScheduleRun;

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
    public String getParentDataset() { return parentDataset; }
    public void setParentDataset(String parentDataset) { this.parentDataset = parentDataset; }
    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
    public String getKeysSql() { return keysSql; }
    public void setKeysSql(String keysSql) { this.keysSql = keysSql; }
    public String getIncrementalFilter() { return incrementalFilter; }
    public void setIncrementalFilter(String incrementalFilter) { this.incrementalFilter = incrementalFilter; }
    public String getKeysEndpoint() { return keysEndpoint; }
    public void setKeysEndpoint(String keysEndpoint) { this.keysEndpoint = keysEndpoint; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getScheduleTime() { return scheduleTime; }
    public void setScheduleTime(String scheduleTime) { this.scheduleTime = scheduleTime; }
    public Integer getScheduleWeekday() { return scheduleWeekday; }
    public void setScheduleWeekday(Integer scheduleWeekday) { this.scheduleWeekday = scheduleWeekday; }
    public Integer getScheduleIntervalMin() { return scheduleIntervalMin; }
    public void setScheduleIntervalMin(Integer scheduleIntervalMin) { this.scheduleIntervalMin = scheduleIntervalMin; }
    public Instant getLastScheduleRun() { return lastScheduleRun; }
    public void setLastScheduleRun(Instant lastScheduleRun) { this.lastScheduleRun = lastScheduleRun; }
    public Instant getNextScheduleRun() { return nextScheduleRun; }
    public void setNextScheduleRun(Instant nextScheduleRun) { this.nextScheduleRun = nextScheduleRun; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
