package com.reporthub.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 锌耗日结台账（液位法） */
@Entity
@Table(name = "zn_ledger")
public class ZnLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate workDate;

    /** 锅长 m */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal potLength;

    /** 锅宽 m */
    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal potWidth;

    /** 锌液密度 g/cm³ */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal zincDensity;

    /** 期初读数 mm（从上沿往下量） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal h0;

    /** 期末读数 mm */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal h1;

    /** 液面变化 mm = h1-h0，正=下降 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal levelDropMm;

    /** 量程 kg/mm = L*W*rho */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal kKgPerMm;

    /** 加锌合计 kg */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal addZincKg = BigDecimal.ZERO;

    /** 液位折算锌耗 kg */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal levelZincKg;

    /** 当日锌耗 kg */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal dayZincKg;

    /** 加锌明细 [{t,w}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String addsJson = "[]";

    @Column(nullable = false, columnDefinition = "text")
    private String note = "";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public BigDecimal getPotLength() { return potLength; }
    public void setPotLength(BigDecimal potLength) { this.potLength = potLength; }
    public BigDecimal getPotWidth() { return potWidth; }
    public void setPotWidth(BigDecimal potWidth) { this.potWidth = potWidth; }
    public BigDecimal getZincDensity() { return zincDensity; }
    public void setZincDensity(BigDecimal zincDensity) { this.zincDensity = zincDensity; }
    public BigDecimal getH0() { return h0; }
    public void setH0(BigDecimal h0) { this.h0 = h0; }
    public BigDecimal getH1() { return h1; }
    public void setH1(BigDecimal h1) { this.h1 = h1; }
    public BigDecimal getLevelDropMm() { return levelDropMm; }
    public void setLevelDropMm(BigDecimal levelDropMm) { this.levelDropMm = levelDropMm; }
    public BigDecimal getkKgPerMm() { return kKgPerMm; }
    public void setkKgPerMm(BigDecimal kKgPerMm) { this.kKgPerMm = kKgPerMm; }
    public BigDecimal getAddZincKg() { return addZincKg; }
    public void setAddZincKg(BigDecimal addZincKg) { this.addZincKg = addZincKg; }
    public BigDecimal getLevelZincKg() { return levelZincKg; }
    public void setLevelZincKg(BigDecimal levelZincKg) { this.levelZincKg = levelZincKg; }
    public BigDecimal getDayZincKg() { return dayZincKg; }
    public void setDayZincKg(BigDecimal dayZincKg) { this.dayZincKg = dayZincKg; }
    public String getAddsJson() { return addsJson; }
    public void setAddsJson(String addsJson) { this.addsJson = addsJson; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
