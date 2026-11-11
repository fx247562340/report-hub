-- 客户月度黑白件汇总细化：增加 产品(物料族) 维度，带上项目号/合同号
-- 产品 = 物料名去掉末尾 黑件/白件 后缀（family 变换），使同一产品族的入库/出库落在同一行
-- 项目号 = U9 Project.Code（合同号 2026AF-DX-xxx / SO 单号），仅白件出货单带值，黑件杂收为空
-- 注意：ReturnFields 必须显式请求 Project.Code，否则原始行里没有该字段

UPDATE api_endpoints SET body_template = $bt$
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
$bt$, updated_at = now() WHERE code = 'u9c_misc_rcv_l';

UPDATE api_endpoints SET body_template = $bt$
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
$bt$, updated_at = now() WHERE code = 'u9c_ship_l';

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_u9c_misc_rcv_l', 'ItemInfo.ItemName', 'product', 'family', 55),
  ('string', 'ds_u9c_misc_rcv_l', 'Project.Code', 'project_no', NULL, 56),
  ('string', 'ds_u9c_ship_l', 'ItemInfo.ItemName', 'product', 'family', 55),
  ('string', 'ds_u9c_ship_l', 'Project.Code', 'project_no', NULL, 56)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

UPDATE reports SET
  name = '客户月度黑白件汇总表（按产品）',
  description = 'U9黑件杂收(BF003)入库与白件出货(BF004)出库，按月×客户×产品汇总吨数与笔数，项目号取白件出货单',
  fields_json = $fn$
[
  {"key": "month", "label": "月份", "from": "month"},
  {"key": "project_no", "label": "项目号", "from": "project_no"},
  {"key": "customer", "label": "客户", "from": "customer"},
  {"key": "product", "label": "产品", "from": "product"},
  {"key": "in_qty", "label": "黑件入库量(吨)", "from": "in_qty"},
  {"key": "in_cnt", "label": "入库笔数", "from": "in_cnt"},
  {"key": "out_qty", "label": "白件出库量(吨)", "from": "out_qty"},
  {"key": "out_cnt", "label": "出库笔数", "from": "out_cnt"},
  {"key": "diff", "label": "出-入差额(吨)", "from": "diff"}
]
$fn$,
  filters_json = $ff$
[
  {"key": "month", "label": "月份", "op": "month", "options": []},
  {"key": "customer", "label": "客户", "op": "like", "options": []},
  {"key": "product", "label": "产品", "op": "like", "options": []}
]
$ff$,
  group_json = $gn$
{
  "by": ["month", "customer", "product"],
  "aggs": [
    {"key": "project_no", "fn": "join", "field": "project_no"},
    {"key": "in_qty", "fn": "sum", "field": "in_qty"},
    {"key": "in_cnt", "fn": "count", "field": "in_qty"},
    {"key": "out_qty", "fn": "sum", "field": "out_qty"},
    {"key": "out_cnt", "fn": "count", "field": "out_qty"}
  ]
}
$gn$,
  order_by_json = '[{"field":"month","dir":"desc"},{"field":"customer","dir":"asc"},{"field":"product","dir":"asc"}]',
  updated_at = now()
WHERE code = 'rpt_cust_month_flow';
