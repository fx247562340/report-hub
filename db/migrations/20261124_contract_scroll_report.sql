-- 北方安防公司智能镀锌事业部销售合同滚动统计报表（rpt_contract_scroll）
-- 1. 新增 U9 列表级数据集：
--    - ds_u9c_complete_bf004 : BF004 成品库白件完工入库（CompleteRpt.RcvQtyByWhUOM）
--    - ds_u9c_issue_bf003    : BF003 客供库黑件生产领料出库（IssueDocLine.IssuedQty）
--    - ds_u9c_soline_galv    : 镀锌销售订单行（ItemInfo.ItemCode LIKE '1104%'）
--    - ds_u9c_arbill_galv    : 镀锌应收/开票明细行（Item.ItemCode LIKE '1104%'）
--    - ds_u9c_arbill_misc    : 手工期初应收行（SrcType = '手工录入'，用于2026年以前合同回款核销溯源）
--    - ds_u9c_recbill_use    : 收款单用途行（RecBillUseLine）
--    - ds_u9c_arapply_l      : 应收核销单行（ARApplyLine）
-- 2. 注册 rpt_contract_scroll 报表元数据（10月期初基线由后端启动时幂等导入 contract_scroll_baselines）

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, body_type, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES
(
  'u9c_complete_bf004', 'U9完工入库列表(BF004白件入库)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_issue_bf003', 'U9生产领料行(BF003黑件出库)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_soline_galv', 'U9镀锌销售订单行(1104*)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_arbill_galv', 'U9镀锌应收开票行(1104*)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_arbill_misc', 'U9手工录入应收行(期初欠款单)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_recbill_use', 'U9收款单用途行', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
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
$bt$, '{}', 'Data', NULL, 'none', now()
),
(
  'u9c_arapply_l', 'U9应收核销明细行', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
{
  "EntityFullName": "UFIDA.U9.AR.ARApply.ARApplyLine",
  "ReturnFields": [
    "ID", "ARApplyHead.DocNo", "ARApplyHead.BusinessDate", "DRDocNo", "CRDocNo"
  ],
  "PageSize": {{pageSize}},
  "PageIndex": {{page}}
}
$bt$, '{}', 'Data', NULL, 'none', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, body_type = EXCLUDED.body_type,
  body_template = EXCLUDED.body_template, headers_template = EXCLUDED.headers_template,
  list_path = EXCLUDED.list_path, total_path = EXCLUDED.total_path,
  pagination = EXCLUDED.pagination, updated_at = now();

INSERT INTO datasets (code, name, endpoint_code, fetch_hint, updated_at)
VALUES
  ('ds_u9c_complete_bf004', 'U9成品库白件入库列表', 'u9c_complete_bf004', 'list', now()),
  ('ds_u9c_issue_bf003',    'U9客供库黑件出库行',   'u9c_issue_bf003',    'list', now()),
  ('ds_u9c_soline_galv',    'U9镀锌销售订单行',     'u9c_soline_galv',    'list', now()),
  ('ds_u9c_arbill_galv',    'U9镀锌应收开票行',     'u9c_arbill_galv',    'list', now()),
  ('ds_u9c_arbill_misc',    'U9手工录入期初应收行', 'u9c_arbill_misc',    'list', now()),
  ('ds_u9c_recbill_use',    'U9收款单用途行',       'u9c_recbill_use',    'list', now()),
  ('ds_u9c_arapply_l',      'U9应收核销明细行',     'u9c_arapply_l',      'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code,
  fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

INSERT INTO sync_configs (dataset_code, enabled, mode, page_size, max_pages, notes,
                          incremental_filter, schedule_type, schedule_time, updated_at)
VALUES
  ('ds_u9c_complete_bf004', TRUE, 'list', 2000, 10, 'U9成品库BF004完工入库列表',
   '{"filtersAppendMonths": {"Field": "DocNo", "Prefix": "GC-", "SinceYM": "{{sinceYM}}"}}', 'daily', '02:15', now()),
  ('ds_u9c_issue_bf003',    TRUE, 'list', 2000, 10, 'U9客供库BF003黑件领料出库行',
   '{"filtersAppendMonths": {"Field": "IssueDoc.DocNo", "Prefix": "LL-", "SinceYM": "{{sinceYM}}"}}', 'daily', '02:20', now()),
  ('ds_u9c_soline_galv',    TRUE, 'list', 2000, 5,  'U9镀锌销售订单行(1104*)',
   NULL, 'daily', '02:25', now()),
  ('ds_u9c_arbill_galv',    TRUE, 'list', 2000, 5,  'U9镀锌应收开票行(1104*)',
   NULL, 'daily', '02:30', now()),
  ('ds_u9c_arbill_misc',    TRUE, 'list', 2000, 3,  'U9手工录入期初应收行',
   NULL, 'daily', '02:32', now()),
  ('ds_u9c_recbill_use',    TRUE, 'list', 2000, 5,  'U9收款单用途行',
   NULL, 'daily', '02:35', now()),
  ('ds_u9c_arapply_l',      TRUE, 'list', 2000, 5,  'U9应收核销明细行',
   NULL, 'daily', '02:38', now())
ON CONFLICT (dataset_code) DO UPDATE SET
  enabled = EXCLUDED.enabled, mode = EXCLUDED.mode, page_size = EXCLUDED.page_size,
  max_pages = EXCLUDED.max_pages, notes = EXCLUDED.notes,
  incremental_filter = EXCLUDED.incremental_filter,
  schedule_type = EXCLUDED.schedule_type, schedule_time = EXCLUDED.schedule_time,
  updated_at = now();
