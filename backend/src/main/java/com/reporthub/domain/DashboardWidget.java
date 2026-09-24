package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 首页数据看板卡片配置 */
@Entity
@Table(name = "dashboard_widgets")
public class DashboardWidget {
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String reportCode;

    /** 卡片标题 */
    @Column(nullable = false, length = 64)
    private String title;

    /** count | sum | avg | min | max */
    @Column(nullable = false, length = 16)
    private String metric = "count";

    /** sum/avg/min/max 的字段 key；count 可空 */
    @Column(length = 64)
    private String field;

    /** 默认筛选 JSON，如 {"endImmersionTime":{"start":"..."}} */
    @Column(columnDefinition = "text")
    private String filtersJson = "{}";

    /** today | week | month — 指标统计时间窗，最长一个月 */
    @Column(length = 16)
    private String timeRange = "month";

    /** 时间窗绑定的筛选参数名，如 endImmersionTime；空则取报表首个 date_range */
    @Column(length = 64)
    private String dateFilterKey;

    /** 数值格式说明，如 行 / kg / 元 */
    @Column(length = 32)
    private String unit;

    @Column(nullable = false)
    private Integer sort = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getReportCode() { return reportCode; }
    public void setReportCode(String reportCode) { this.reportCode = reportCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public String getTimeRange() { return timeRange; }
    public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
    public String getDateFilterKey() { return dateFilterKey; }
    public void setDateFilterKey(String dateFilterKey) { this.dateFilterKey = dateFilterKey; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
