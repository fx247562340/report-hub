package com.reporthub.engine;

import com.reporthub.domain.RelationDef;
import com.reporthub.service.ContractScrollService;

import java.util.List;
import java.util.Map;

/**
 * 报表多表关联与内置核算血缘的唯一代码注册表（不再依赖数据库 relations 表）。
 *
 * <p>包含两类定义：
 * 1. 通用多表关联引擎（{@link ReportQueryEngine}）使用的关联规则（挂次日报表、挂次白件重量差异表）；
 * 2. 内置专用核算引擎（{@link ContractScrollService}）的 10 表血缘与核算环节描述，供前端血缘图只读展示。
 */
public final class ReportRelationRegistry {

    private ReportRelationRegistry() {}

    public record BuiltinStage(String id, String tag, String title, String subtitle, String meta, String desc) {}

    public record BuiltinInput(String dsCode, String role, String stageId, String edgeLabel, String detail) {}

    public record BuiltinLineage(
            String engineLabel,
            String outputBadge,
            String outputMeta,
            String callout,
            String baselineTable,
            String baselineTitle,
            String baselineTag,
            String baselineBadge,
            String baselineDesc,
            String baselineStageId,
            String baselineEdgeLabel,
            String baselineEdgeDetail,
            List<BuiltinStage> stages,
            List<BuiltinInput> inputs
    ) {}

    private static final Map<String, List<RelationDef>> REPORT_RELATIONS = Map.of(
            "rpt_hang_complete", List.of(
                    new RelationDef(
                            "rel_hang_u9c_complete",
                            "rpt_hang_complete",
                            "ds_hang",
                            "ds_u9c_complete",
                            "N-1",
                            "left",
                            "[{\"leftField\":\"hang_no\",\"rightField\":\"doc_no\"}]",
                            "child_lookup",
                            "{\"endpoint\":\"u9c_complete_query\",\"detailEndpoint\":\"u9c_complete_query\",\"pathParam\":\"id\",\"concurrency\":8}",
                            "flat",
                            0
                    ),
                    new RelationDef(
                            "rel_complete_u9c_mo",
                            "rpt_hang_complete",
                            "ds_u9c_complete",
                            "ds_u9c_mo",
                            "N-1",
                            "left",
                            "[{\"leftField\":\"mo_no\",\"rightField\":\"mo_no\"}]",
                            "child_batch",
                            "{\"endpoint\":\"u9c_mo_query\",\"keyParam\":\"ids\",\"maxKeysPerCall\":50}",
                            "flat",
                            1
                    ),
                    new RelationDef(
                            "rel_hang_zinc",
                            "rpt_hang_complete",
                            "ds_hang",
                            "ds_mes_zinc_thickness",
                            "1-1",
                            "left",
                            "[{\"leftField\":\"hang_no\",\"rightField\":\"hang_no_zinc\"}]",
                            "child_lookup",
                            "{\"keyField\":\"hang_no\",\"concurrency\":8,\"endpoint\":\"mes_zinc_thickness\"}",
                            "flat",
                            10
                    )
            ),
            "rpt_white_weight_diff", List.of(
                    new RelationDef(
                            "rel_wdiff_hang_u9c",
                            "rpt_white_weight_diff",
                            "ds_hang",
                            "ds_u9c_complete",
                            "N-1",
                            "left",
                            "[{\"leftField\":\"hang_no\",\"rightField\":\"doc_no\"}]",
                            "child_lookup",
                            "{\"endpoint\":\"u9c_complete_query\",\"detailEndpoint\":\"u9c_complete_query\",\"pathParam\":\"id\",\"concurrency\":8}",
                            "flat",
                            0
                    )
            )
    );

    private static final BuiltinLineage CONTRACT_SCROLL_LINEAGE = new BuiltinLineage(
            "内置滚动引擎",
            "42 列 · 双层表头",
            "按月滚动查询与分组导出",
            "《销售合同滚动统计报表》由后端内置核算引擎驱动，自动整合 2026-10 期初基线与 9 张 U9 业务表。",
            "contract_scroll_baselines",
            "期初基线表",
            "2026-10 基线",
            "期初起点",
            "保存 2026-10 期初黑白件库存、期初累计开票/回款及纸质合同号与 U9 项目号映射关系。",
            "stage_contract",
            "期初基线行",
            "导入 2026-10 期初结存与合同号映射作为月度滚动起点",
            List.of(
                    new BuiltinStage(
                            "stage_contract",
                            "核算环节 1",
                            "合同主档与新单合并",
                            "期初基线 + U9 1104 新单",
                            "自动合并一合同多料品行",
                            "以 2026-10 基线行作为起点，自动纳入 2026-11 及之后 U9 新签镀锌销售订单行。"
                    ),
                    new BuiltinStage(
                            "stage_stock",
                            "核算环节 2",
                            "黑白件月度滚动结存",
                            "BF003 黑件 · BF004 白件",
                            "期末 = 期初 + 累计入 - 累计出",
                            "按项目号、客户+料品后缀逐月归集四类出入库单据，滚动推导黑白件线上暂存与期末库存。"
                    ),
                    new BuiltinStage(
                            "stage_finance",
                            "核算环节 3",
                            "开票回款核销与欠款",
                            "应收单 + 收款 + 核销链",
                            "已发未开 · 已开未回 · 总欠款",
                            "经 ARApplyLine 将收款单精准核销至对应应收与合同，未核销预收按客户欠款 FIFO 分配。"
                    )
            ),
            List.of(
                    new BuiltinInput("ds_u9c_soline_galv", "新单发现", "stage_contract", "1104 新单发现",
                            "按 SO.DocNo / Project.Code 自动发现 2026-11 起新签镀锌销售订单行及单价、预计吨位"),
                    new BuiltinInput("ds_u9c_misc_rcv_l", "黑件来料", "stage_stock", "客户+料品分摊",
                            "客供原材料库杂收单行（MiscRcv003），按客户与料品归集/分摊至对应合同行 T 列"),
                    new BuiltinInput("ds_u9c_issue_bf003", "黑件出库", "stage_stock", "项目号匹配",
                            "客供原材料库生产领料行，按 Project.Code 或客户+料品匹配至合同行 U 列"),
                    new BuiltinInput("ds_u9c_complete_bf004", "白件入库", "stage_stock", "项目号匹配",
                            "镀锌成品库完工报告入库数量，归集至合同行 W 列"),
                    new BuiltinInput("ds_u9c_ship_l", "白件发货", "stage_stock", "项目号匹配",
                            "镀锌成品库出货单行发货吨位，归集至合同行 X 列并推导累计发货金额"),
                    new BuiltinInput("ds_u9c_arbill_galv", "开票应收", "stage_finance", "发票/应收归集",
                            "镀锌应收单行（已录发票号计入 Z 列开票金额，全部应收计入未做应收核对）"),
                    new BuiltinInput("ds_u9c_arbill_misc", "期初应收", "stage_finance", "期初挂账",
                            "手工录入期初应收单（SrcType=0），参与应收核销与欠款核对"),
                    new BuiltinInput("ds_u9c_recbill_use", "收款用途", "stage_finance", "核销追溯",
                            "收款单用途行（RecBillUseLine）结合核销链归集至合同行 AA 列回款金额"),
                    new BuiltinInput("ds_u9c_arapply_l", "应收核销", "stage_finance", "核销链分配",
                            "连接收款用途行与应收单号，实现精准按合同分配回款（未核销预收按 FIFO 归属）")
            )
    );

    public static List<RelationDef> relationsFor(String reportCode) {
        if (reportCode == null) return List.of();
        return REPORT_RELATIONS.getOrDefault(reportCode, List.of());
    }

    public static BuiltinLineage builtinLineageFor(String reportCode) {
        if (ContractScrollService.REPORT_CODE.equals(reportCode)) {
            return CONTRACT_SCROLL_LINEAGE;
        }
        return null;
    }
}
