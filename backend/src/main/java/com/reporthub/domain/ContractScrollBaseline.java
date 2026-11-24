package com.reporthub.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 销售合同滚动统计报表 · 2026年10月期初基线与纸质合同号映射。
 * 仅维护 2026-10 期初静态基线与合同归属映射；2026-10 起各月经营发生额均由上游系统实时/同步聚合。
 */
@Entity
@Table(name = "contract_scroll_baselines", indexes = {
        @Index(name = "idx_csb_sort", columnList = "sortOrder"),
        @Index(name = "idx_csb_contract", columnList = "contractNo")
})
public class ContractScrollBaseline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 排序号，保持与总经理滚动表一致的行顺序 */
    @Column(nullable = false)
    private Integer sortOrder = 0;

    /** pre2026 | contract | uncontracted */
    @Column(nullable = false, length = 24)
    private String rowKind = "contract";

    /** 序号（A列）：2026年以前合同为空，正式合同 1..116，无合同散户 120.. */
    private Integer seqNo;

    /** 展示合同号（B列），如 2026AF-DX-001 / 2026年以前合同 */
    @Column(length = 128)
    private String contractNo = "";

    /** 关联的 U9 项目号或销售订单号列表（逗号分隔），如 2026AF-DX-110,SO20261023011 */
    @Column(length = 512)
    private String u9ProjectCodes = "";

    /** 客户名称（C列） */
    @Column(length = 160)
    private String customerName = "";

    /** 签订日期（D列，yyyy-MM-dd） */
    @Column(length = 16)
    private String signDate;

    /** 签订人（E列） */
    @Column(length = 64)
    private String salesman = "";

    /** 产品名称（F列） */
    @Column(length = 160)
    private String productName = "";

    /** 对应 U9 白件料号（1104xxxx-yyyy），用于一合同多料品精确分流（自动同缀匹配 1205 黑件） */
    @Column(length = 64)
    private String itemCode = "";

    /** 单价（G列，含税元/吨） */
    private Double unitPrice;

    /** 预计总吨位/开口（H列） */
    private Double estTonnage;

    /** 预计合同金额（I列） */
    private Double estAmount;

    /** 结算金额（J列） */
    private Double settleAmount;

    /** 2026-10 期初黑件库存（K列） */
    @Column(nullable = false)
    private Double openBlackStock = 0.0;

    /** 2026-10 期初白件库存（L列） */
    @Column(nullable = false)
    private Double openWhiteStock = 0.0;

    /** 2026-10 期初白件累计发货（M列，截至2026-09末累计） */
    @Column(nullable = false)
    private Double openWhiteShipCum = 0.0;

    /** 2026-10 期初开票金额（O列，截至2026-09末累计） */
    @Column(nullable = false)
    private Double openInvoice = 0.0;

    /** 2026-10 期初回款金额（P列，截至2026-09末累计） */
    @Column(nullable = false)
    private Double openPayment = 0.0;

    /** 截至 2026-09 末黑件累计来料（用于滚动计算 AB 列） */
    @Column(nullable = false)
    private Double openBlackInCum = 0.0;

    /** 截至 2026-09 末黑件累计出库（用于滚动计算 AC 列） */
    @Column(nullable = false)
    private Double openBlackOutCum = 0.0;

    /** 截至 2026-09 末白件累计入库（用于滚动计算 AE 列） */
    @Column(nullable = false)
    private Double openWhiteInCum = 0.0;

    /** 备注（AP列） */
    @Column(length = 512)
    private String remark = "";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getRowKind() { return rowKind; }
    public void setRowKind(String rowKind) { this.rowKind = rowKind; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public String getU9ProjectCodes() { return u9ProjectCodes; }
    public void setU9ProjectCodes(String u9ProjectCodes) { this.u9ProjectCodes = u9ProjectCodes; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getSignDate() { return signDate; }
    public void setSignDate(String signDate) { this.signDate = signDate; }
    public String getSalesman() { return salesman; }
    public void setSalesman(String salesman) { this.salesman = salesman; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public Double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Double unitPrice) { this.unitPrice = unitPrice; }
    public Double getEstTonnage() { return estTonnage; }
    public void setEstTonnage(Double estTonnage) { this.estTonnage = estTonnage; }
    public Double getEstAmount() { return estAmount; }
    public void setEstAmount(Double estAmount) { this.estAmount = estAmount; }
    public Double getSettleAmount() { return settleAmount; }
    public void setSettleAmount(Double settleAmount) { this.settleAmount = settleAmount; }
    public Double getOpenBlackStock() { return openBlackStock; }
    public void setOpenBlackStock(Double openBlackStock) { this.openBlackStock = openBlackStock; }
    public Double getOpenWhiteStock() { return openWhiteStock; }
    public void setOpenWhiteStock(Double openWhiteStock) { this.openWhiteStock = openWhiteStock; }
    public Double getOpenWhiteShipCum() { return openWhiteShipCum; }
    public void setOpenWhiteShipCum(Double openWhiteShipCum) { this.openWhiteShipCum = openWhiteShipCum; }
    public Double getOpenInvoice() { return openInvoice; }
    public void setOpenInvoice(Double openInvoice) { this.openInvoice = openInvoice; }
    public Double getOpenPayment() { return openPayment; }
    public void setOpenPayment(Double openPayment) { this.openPayment = openPayment; }
    public Double getOpenBlackInCum() { return openBlackInCum; }
    public void setOpenBlackInCum(Double openBlackInCum) { this.openBlackInCum = openBlackInCum; }
    public Double getOpenBlackOutCum() { return openBlackOutCum; }
    public void setOpenBlackOutCum(Double openBlackOutCum) { this.openBlackOutCum = openBlackOutCum; }
    public Double getOpenWhiteInCum() { return openWhiteInCum; }
    public void setOpenWhiteInCum(Double openWhiteInCum) { this.openWhiteInCum = openWhiteInCum; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
