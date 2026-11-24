package com.reporthub.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.domain.*;
import com.reporthub.repo.*;
import com.reporthub.service.ContractScrollService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * 幂等初始化「销售合同滚动统计报表」所需的 U9 列表接口、同步配置、报表元数据与 2026-10 期初基线。
 * 不覆盖已有数据源凭证或其它报表定义，本地开发与线上 git pull 重建均可零手工 SQL 自举。
 */
@Component
@Order(20)
public class ContractScrollInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ContractScrollInitializer.class);

    private final DataSourceRepo dataSourceRepo;
    private final ApiEndpointRepo endpointRepo;
    private final DatasetRepo datasetRepo;
    private final SyncConfigRepo syncConfigRepo;
    private final ReportRepo reportRepo;
    private final ContractScrollBaselineRepo baselineRepo;
    private final ObjectMapper mapper;
    private final ReportHubProperties props;
    private final JdbcTemplate jdbc;

    public ContractScrollInitializer(DataSourceRepo dataSourceRepo,
                                     ApiEndpointRepo endpointRepo,
                                     DatasetRepo datasetRepo,
                                     SyncConfigRepo syncConfigRepo,
                                     ReportRepo reportRepo,
                                     ContractScrollBaselineRepo baselineRepo,
                                     ObjectMapper mapper,
                                     ReportHubProperties props,
                                     JdbcTemplate jdbc) {
        this.dataSourceRepo = dataSourceRepo;
        this.endpointRepo = endpointRepo;
        this.datasetRepo = datasetRepo;
        this.syncConfigRepo = syncConfigRepo;
        this.reportRepo = reportRepo;
        this.baselineRepo = baselineRepo;
        this.mapper = mapper;
        this.props = props;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        try {
            dropUnusedPayloadGinIndex();
            ensureDatasourceIfEmpty();
            ensureEndpointsAndDatasets();
            ensureReportDef();
            ensureBaselinesSeeded();
        } catch (Exception e) {
            log.warn("ContractScrollInitializer warning: {}", e.getMessage(), e);
        }
    }

    /** 清理未使用的 JSONB GIN 索引，避免 sync_records 空间 20x 膨胀 */
    private void dropUnusedPayloadGinIndex() {
        try {
            jdbc.execute("DROP INDEX IF EXISTS ix_sync_records_payload");
        } catch (Exception e) {
            log.debug("dropUnusedPayloadGinIndex skipped: {}", e.getMessage());
        }
    }

    private void ensureDatasourceIfEmpty() {
        if (!props.isSeed()) return;
        if (dataSourceRepo.findById("u9c_erp").isPresent()) return;
        DataSourceDef ds = new DataSourceDef();
        ds.setCode("u9c_erp");
        ds.setName("U9 Cloud ERP");
        ds.setBaseUrl("https://erp.btbfaf.com/U9C");
        ds.setAuthType("u9c_oauth");
        ds.setAuthConfig("""
                {"clientId":"bf","clientSecret":"09bd81f413f4451585b6c0b7a0269b11","entCode":"001","userCode":"af30045","orgCode":"0101","ttlSeconds":240}
                """.trim());
        ds.setUpdatedAt(Instant.now());
        dataSourceRepo.save(ds);
        log.info("Seeded default u9c_erp datasource for local environment.");
    }

    private void ensureEndpointsAndDatasets() {
        // 仅在缺失时补齐原有的黑件杂收与白件出货接口，避免覆盖已有配置
        ensureEndpointIfMissing("u9c_misc_rcv_l", "U9杂收行(BF003黑件入库)", """
                {
                  "EntityFullName": "UFIDA.U9.InvDoc.MiscRcv.MiscRcvTransL",
                  "ReturnFields": [
                    "MiscRcvTrans.DocNo", "MiscRcvTrans.BusinessDate", "MiscRcvTrans.DocType.Code",
                    "MiscRcvTrans.DocType.Name", "MiscRcvTrans.Status", "DocLineNo", "StoreUOMQty",
                    "Wh.Code", "Project.Code", "ItemInfo.ItemCode", "ItemInfo.ItemName",
                    "ItemInfo.ItemID.MainItemCategory.Code", "ItemInfo.ItemID.MainItemCategory.Name"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "Wh.Code", "Operator": "eq", "Value": "BF003"},
                    {"Field": "MiscRcvTrans.MiscRcvDocType.Code", "Operator": "eq", "Value": "MiscRcv003"}
                  ]
                }
                """, "ds_u9c_misc_rcv_l", "U9黑件杂收行", 500, 20,
                "{\"filtersAppendMonths\": {\"Field\": \"MiscRcvTrans.DocNo\", \"Prefix\": \"Mis\", \"SinceYM\": \"{{sinceYM}}\"}}", "02:00");

        ensureEndpointIfMissing("u9c_ship_l", "U9出货行(BF004白件出库)", """
                {
                  "EntityFullName": "UFIDA.U9.SM.Ship.ShipLine",
                  "ReturnFields": [
                    "Ship.DocNo", "Ship.BusinessDate", "Ship.Status", "Ship.DocType.Code", "Ship.DocType.Name",
                    "DocLineNo", "ShipQtyTUAmount", "Wh.Code", "Project.Code", "ItemInfo.ItemCode", "ItemInfo.ItemName",
                    "ItemInfo.ItemID.MainItemCategory.Code", "ItemInfo.ItemID.MainItemCategory.Name"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "Wh", "Operator": "eq", "Value": "1002605241220237"}
                  ]
                }
                """, "ds_u9c_ship_l", "U9白件出货行", 500, 20,
                "{\"filtersAppendMonths\": {\"Field\": \"Ship.DocNo\", \"Prefix\": \"SM\", \"SinceYM\": \"{{sinceYM}}\"}}", "02:10");

        // 7 个新增合同滚动报表专用 U9 数据集
        upsertU9ListDataset("u9c_complete_bf004", "U9完工入库列表(BF004白件入库)", """
                {
                  "EntityFullName": "UFIDA.U9.MO.Complete.CompleteRpt",
                  "ReturnFields": [
                    "ID", "DocNo", "BusinessDate", "ActualRcvTime", "DocState",
                    "RcvWh.Code", "RcvWh.Name", "Item.Code", "Item.Name", "Item.MainItemCategory.Name",
                    "CompleteQty", "RcvQtyByWhUOM", "MO.DocNo", "MO.Project.Code", "Project.Code"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "RcvWh.Code", "Operator": "eq", "Value": "BF004"}
                  ]
                }
                """, "ds_u9c_complete_bf004", "U9成品库白件入库列表", 2000, 10,
                "{\"filtersAppendMonths\": {\"Field\": \"DocNo\", \"Prefix\": \"GC-\", \"SinceYM\": \"{{sinceYM}}\"}}", "02:15");

        upsertU9ListDataset("u9c_issue_bf003", "U9生产领料行(BF003黑件出库)", """
                {
                  "EntityFullName": "UFIDA.U9.MO.Issue.IssueDocLine",
                  "ReturnFields": [
                    "ID", "IssueDoc.DocNo", "IssueDoc.BusinessDate", "IssueDoc.DocState",
                    "Wh.Code", "Wh.Name", "Item.Code", "Item.Name", "Item.MainItemCategory.Name",
                    "IssueQty", "IssuedQty", "MO.DocNo", "MO.Project.Code", "Project.Code"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "Wh.Code", "Operator": "eq", "Value": "BF003"}
                  ]
                }
                """, "ds_u9c_issue_bf003", "U9客供库黑件出库行", 2000, 10,
                "{\"filtersAppendMonths\": {\"Field\": \"IssueDoc.DocNo\", \"Prefix\": \"LL-\", \"SinceYM\": \"{{sinceYM}}\"}}", "02:20");

        upsertU9ListDataset("u9c_soline_galv", "U9镀锌销售订单行(1104*)", """
                {
                  "EntityFullName": "UFIDA.U9.SM.SO.SOLine",
                  "ReturnFields": [
                    "ID", "DocLineNo", "SO.DocNo", "SO.BusinessDate", "SO.Status",
                    "SO.SaleDepartment.Code", "SO.SaleDepartment.Name",
                    "SO.OrderBy.Code", "SO.OrderBy.Name", "SO.Seller.Name",
                    "ItemInfo.ItemCode", "ItemInfo.ItemName", "ItemInfo.ItemID.MainItemCategory.Name",
                    "OrderByQtyTU", "FinallyPriceTC", "TotalMoneyTC", "Project.Code", "Memo", "SO.Memo"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "ItemInfo.ItemCode", "Operator": "like", "Value": "1104%"}
                  ]
                }
                """, "ds_u9c_soline_galv", "U9镀锌销售订单行", 2000, 5, null, "02:25");

        upsertU9ListDataset("u9c_arbill_galv", "U9镀锌应收开票行(1104*)", """
                {
                  "EntityFullName": "UFIDA.U9.AR.ARBill.ARBillLine",
                  "ReturnFields": [
                    "ID", "LineNum", "ARBillHead.DocNo", "ARBillHead.BusinessDate", "ARBillHead.AccrueDate",
                    "ARBillHead.DocStatus", "ARBillHead.SrcType", "ARBillHead.Dept.Code",
                    "ARBillHead.AccrueCust.Customer.Code", "ARBillHead.AccrueCust.Customer.Name",
                    "Project.Code", "Item.ItemCode", "Item.ItemName",
                    "TUAmount", "AROCMoney.TotalMoney", "AROCMoney.NonTax", "AROCMoneyBalance.TotalMoney",
                    "SrcBillNum", "InvoiceNum", "InvoiceDate"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "Item.ItemCode", "Operator": "like", "Value": "1104%"}
                  ]
                }
                """, "ds_u9c_arbill_galv", "U9镀锌应收开票行", 2000, 5, null, "02:30");

        upsertU9ListDataset("u9c_arbill_misc", "U9手工录入应收行(期初欠款单)", """
                {
                  "EntityFullName": "UFIDA.U9.AR.ARBill.ARBillLine",
                  "ReturnFields": [
                    "ID", "LineNum", "ARBillHead.DocNo", "ARBillHead.BusinessDate",
                    "ARBillHead.DocStatus", "ARBillHead.SrcType", "ARBillHead.Dept.Code",
                    "ARBillHead.AccrueCust.Customer.Code", "ARBillHead.AccrueCust.Customer.Name",
                    "Project.Code", "Item.ItemCode", "Item.ItemName",
                    "TUAmount", "AROCMoney.TotalMoney", "AROCMoneyBalance.TotalMoney", "InvoiceNum"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}},
                  "Filters": [
                    {"Field": "ARBillHead.SrcType", "Operator": "eq", "Value": "0"}
                  ]
                }
                """, "ds_u9c_arbill_misc", "U9手工录入期初应收行", 2000, 3, null, "02:32");

        upsertU9ListDataset("u9c_recbill_use", "U9收款单用途行", """
                {
                  "EntityFullName": "UFIDA.U9.AR.Receival.RecBillUseLine",
                  "ReturnFields": [
                    "ID", "LineNum", "RecBillHead.DocNo", "RecBillHead.BusinessDate", "RecBillHead.RecDate",
                    "RecBillHead.DocStatus", "RecBillHead.Dept.Code",
                    "RecBillHead.Cust.Customer.Code", "RecBillHead.Cust.Customer.Name", "RecBillHead.Note",
                    "ARBillHead.DocNo", "Project.Code", "Money.OCMoney", "RecProperty"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}}
                }
                """, "ds_u9c_recbill_use", "U9收款单用途行", 2000, 5, null, "02:35");

        upsertU9ListDataset("u9c_arapply_l", "U9应收核销明细行", """
                {
                  "EntityFullName": "UFIDA.U9.AR.ARApply.ARApplyLine",
                  "ReturnFields": [
                    "ID", "ARApplyHead.DocNo", "ARApplyHead.BusinessDate", "DRDocNo", "CRDocNo"
                  ],
                  "PageSize": {{pageSize}},
                  "PageIndex": {{page}}
                }
                """, "ds_u9c_arapply_l", "U9应收核销明细行", 2000, 5, null, "02:38");
    }

    private void ensureEndpointIfMissing(String epCode, String epName, String bodyTpl,
                                         String dsCode, String dsName,
                                         int pageSize, int maxPages, String incFilter, String hhmm) {
        if (endpointRepo.findById(epCode).isPresent() && datasetRepo.findById(dsCode).isPresent()) return;
        upsertU9ListDataset(epCode, epName, bodyTpl, dsCode, dsName, pageSize, maxPages, incFilter, hhmm);
    }

    private void upsertU9ListDataset(String epCode, String epName, String bodyTpl,
                                     String dsCode, String dsName,
                                     int pageSize, int maxPages, String incFilter, String hhmm) {
        ApiEndpointDef ep = endpointRepo.findById(epCode).orElseGet(ApiEndpointDef::new);
        ep.setCode(epCode);
        ep.setName(epName);
        ep.setDataSourceCode("u9c_erp");
        ep.setMethod("POST");
        ep.setPath("/webapi/CommonEntity/Query");
        ep.setBodyType("json");
        ep.setBodyTemplate(bodyTpl.trim());
        ep.setHeadersTemplate("{}");
        ep.setListPath("Data");
        ep.setPagination("none");
        ep.setUpdatedAt(Instant.now());
        endpointRepo.save(ep);

        DatasetDef ds = datasetRepo.findById(dsCode).orElseGet(DatasetDef::new);
        ds.setCode(dsCode);
        ds.setName(dsName);
        ds.setEndpointCode(epCode);
        ds.setFetchHint("list");
        ds.setUpdatedAt(Instant.now());
        datasetRepo.save(ds);

        if (syncConfigRepo.findById(dsCode).isEmpty()) {
            SyncConfig sc = new SyncConfig();
            sc.setDatasetCode(dsCode);
            sc.setEnabled(true);
            sc.setMode("list");
            sc.setPageSize(pageSize);
            sc.setMaxPages(maxPages);
            sc.setNotes(dsName);
            sc.setIncrementalFilter(incFilter);
            sc.setScheduleType("daily");
            sc.setScheduleTime(hhmm);
            sc.setUpdatedAt(Instant.now());
            syncConfigRepo.save(sc);
        }
    }

    private void ensureReportDef() throws Exception {
        ReportDef r = reportRepo.findById(ContractScrollService.REPORT_CODE).orElseGet(ReportDef::new);
        r.setCode(ContractScrollService.REPORT_CODE);
        r.setName("销售合同滚动统计报表");
        r.setDescription("北方安防公司智能镀锌事业部销售合同月度滚动表（合同档案 · 期初 · 当月出入库/开票/回款 · 期末库存与欠款）");
        r.setRootDataset("ds_u9c_soline_galv");
        r.setEnabled(true);
        r.setFieldsJson(mapper.writeValueAsString(ContractScrollService.buildColumns("2026-10")));
        r.setFiltersJson("""
                [
                  {"key": "month", "label": "统计月份", "op": "month", "min": "2026-10", "options": []},
                  {"key": "contract_no", "label": "合同号", "op": "like", "options": []},
                  {"key": "customer_name", "label": "客户名称", "op": "like", "options": []},
                  {"key": "salesman", "label": "签订人", "op": "like", "options": []}
                ]
                """.trim());
        r.setCalcsJson("[]");
        r.setGroupJson("{}");
        r.setOrderByJson("[]");
        r.setExportConfig("{\"custom\":\"contract_scroll\"}");
        r.setPaginationJson("""
                {
                  "defaultPageSize": 200,
                  "pageSizeOptions": [50, 100, 200, 500],
                  "showJump": true,
                  "pageParam": "page",
                  "pageSizeParam": "pageSize",
                  "pageBase": 1
                }
                """.trim());
        r.setUpdatedAt(Instant.now());
        reportRepo.save(r);
    }

    private void ensureBaselinesSeeded() throws Exception {
        if (baselineRepo.count() > 0) return;
        ClassPathResource res = new ClassPathResource("seed/contract_scroll_baselines.json");
        if (!res.exists()) {
            log.warn("seed/contract_scroll_baselines.json not found on classpath");
            return;
        }
        try (InputStream in = res.getInputStream()) {
            List<ContractScrollBaseline> rows = mapper.readValue(in, new TypeReference<List<ContractScrollBaseline>>() {});
            Instant now = Instant.now();
            for (ContractScrollBaseline b : rows) {
                b.setId(null);
                b.setUpdatedAt(now);
            }
            baselineRepo.saveAll(rows);
            log.info("Seeded {} baseline rows for rpt_contract_scroll (2026-10 opening).", rows.size());
        }
    }
}
