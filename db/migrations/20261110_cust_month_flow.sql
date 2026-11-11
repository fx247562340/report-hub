-- 客户月度黑白件汇总：U9 黑件杂收(BF003) 入库 + 白件出货(BF004) 出库，union 后按 月×客户 分组
-- 取数：CommonEntity/Query 全量拉行（PageSize/PageIndex），查询期聚合；勿预聚合落库（会破坏 realtime 模式）

ALTER TABLE reports ADD COLUMN IF NOT EXISTS group_json TEXT DEFAULT '{}';

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, body_type, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'u9c_misc_rcv_l', 'U9杂收行(BF003黑件入库)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
{
  "EntityFullName": "UFIDA.U9.InvDoc.MiscRcv.MiscRcvTransL",
  "ReturnFields": [
    "MiscRcvTrans.DocNo", "MiscRcvTrans.BusinessDate", "MiscRcvTrans.DocType.Code",
    "MiscRcvTrans.DocType.Name", "MiscRcvTrans.Status", "DocLineNo", "StoreUOMQty",
    "Wh.Code", "ItemInfo.ItemCode", "ItemInfo.ItemName",
    "ItemInfo.ItemID.MainItemCategory.Code", "ItemInfo.ItemID.MainItemCategory.Name"
  ],
  "PageSize": {{pageSize}},
  "PageIndex": {{page}},
  "Filters": [
    {"Field": "Wh.Code", "Operator": "eq", "Value": "BF003"},
    {"Field": "MiscRcvTrans.MiscRcvDocType.Code", "Operator": "eq", "Value": "MiscRcv003"}
  ]
}
$bt$,
  '{}', 'Data', NULL, 'none', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, body_type = EXCLUDED.body_type,
  body_template = EXCLUDED.body_template, headers_template = EXCLUDED.headers_template,
  list_path = EXCLUDED.list_path, total_path = EXCLUDED.total_path,
  pagination = EXCLUDED.pagination, updated_at = now();

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, body_type, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'u9c_ship_l', 'U9出货行(BF004白件出库)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
{
  "EntityFullName": "UFIDA.U9.SM.Ship.ShipLine",
  "ReturnFields": [
    "Ship.DocNo", "Ship.BusinessDate", "Ship.Status", "Ship.DocType.Code", "Ship.DocType.Name",
    "DocLineNo", "ShipQtyTUAmount", "Wh.Code", "ItemInfo.ItemCode", "ItemInfo.ItemName",
    "ItemInfo.ItemID.MainItemCategory.Code", "ItemInfo.ItemID.MainItemCategory.Name"
  ],
  "PageSize": {{pageSize}},
  "PageIndex": {{page}},
  "Filters": [
    {"Field": "Wh", "Operator": "eq", "Value": "1002605241220237"}
  ]
}
$bt$,
  '{}', 'Data', NULL, 'none', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, body_type = EXCLUDED.body_type,
  body_template = EXCLUDED.body_template, headers_template = EXCLUDED.headers_template,
  list_path = EXCLUDED.list_path, total_path = EXCLUDED.total_path,
  pagination = EXCLUDED.pagination, updated_at = now();

INSERT INTO datasets (code, name, endpoint_code, fetch_hint, updated_at)
VALUES
  ('ds_u9c_misc_rcv_l', 'U9黑件杂收行', 'u9c_misc_rcv_l', 'list', now()),
  ('ds_u9c_ship_l', 'U9白件出货行', 'u9c_ship_l', 'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code,
  fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

-- 字段映射：month 由 BusinessDate 取 yyyy-mm；黑件 in_qty / 白件 out_qty 分属两侧
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_u9c_misc_rcv_l', 'MiscRcvTrans.BusinessDate', 'biz_date', 'date10', 10),
  ('string', 'ds_u9c_misc_rcv_l', 'MiscRcvTrans.BusinessDate', 'month', 'month', 20),
  ('string', 'ds_u9c_misc_rcv_l', 'ItemInfo.ItemID.MainItemCategory.Name', 'customer', NULL, 30),
  ('string', 'ds_u9c_misc_rcv_l', 'ItemInfo.ItemID.MainItemCategory.Code', 'customer_code', NULL, 40),
  ('string', 'ds_u9c_misc_rcv_l', 'ItemInfo.ItemCode', 'item_code', NULL, 50),
  ('string', 'ds_u9c_misc_rcv_l', 'ItemInfo.ItemName', 'item_name', NULL, 60),
  ('string', 'ds_u9c_misc_rcv_l', 'MiscRcvTrans.DocNo', 'doc_no', NULL, 70),
  ('string', 'ds_u9c_misc_rcv_l', 'DocLineNo', 'doc_line', NULL, 80),
  ('string', 'ds_u9c_misc_rcv_l', 'MiscRcvTrans.DocType.Name', 'doc_type', NULL, 90),
  ('string', 'ds_u9c_misc_rcv_l', 'MiscRcvTrans.Status', 'status', NULL, 100),
  ('string', 'ds_u9c_misc_rcv_l', 'Wh.Code', 'wh_code', NULL, 110),
  ('number', 'ds_u9c_misc_rcv_l', 'StoreUOMQty', 'in_qty', NULL, 120)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_u9c_ship_l', 'Ship.BusinessDate', 'biz_date', 'date10', 10),
  ('string', 'ds_u9c_ship_l', 'Ship.BusinessDate', 'month', 'month', 20),
  ('string', 'ds_u9c_ship_l', 'ItemInfo.ItemID.MainItemCategory.Name', 'customer', NULL, 30),
  ('string', 'ds_u9c_ship_l', 'ItemInfo.ItemID.MainItemCategory.Code', 'customer_code', NULL, 40),
  ('string', 'ds_u9c_ship_l', 'ItemInfo.ItemCode', 'item_code', NULL, 50),
  ('string', 'ds_u9c_ship_l', 'ItemInfo.ItemName', 'item_name', NULL, 60),
  ('string', 'ds_u9c_ship_l', 'Ship.DocNo', 'doc_no', NULL, 70),
  ('string', 'ds_u9c_ship_l', 'DocLineNo', 'doc_line', NULL, 80),
  ('string', 'ds_u9c_ship_l', 'Ship.DocType.Name', 'doc_type', NULL, 90),
  ('string', 'ds_u9c_ship_l', 'Ship.Status', 'status', NULL, 100),
  ('string', 'ds_u9c_ship_l', 'Wh.Code', 'wh_code', NULL, 110),
  ('number', 'ds_u9c_ship_l', 'ShipQtyTUAmount', 'out_qty', NULL, 120)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- union 关系：两侧行追加，不按字段关联
INSERT INTO relations (code, report_code, left_dataset, right_dataset, join_type, cardinality,
                       on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at)
VALUES (
  'rel_cust_flow_union', 'rpt_cust_month_flow', 'ds_u9c_misc_rcv_l', 'ds_u9c_ship_l',
  'union', 'N-N', '[]', 'dual_list', '{}', 'flat', 0, now()
)
ON CONFLICT (code) DO UPDATE SET
  report_code = EXCLUDED.report_code, left_dataset = EXCLUDED.left_dataset,
  right_dataset = EXCLUDED.right_dataset, join_type = EXCLUDED.join_type,
  cardinality = EXCLUDED.cardinality, on_fields = EXCLUDED.on_fields,
  fetch_mode = EXCLUDED.fetch_mode, fetch_config = EXCLUDED.fetch_config,
  expand_mode = EXCLUDED.expand_mode, sort = EXCLUDED.sort, updated_at = now();

INSERT INTO reports (code, name, description, root_dataset, enabled,
                     fields_json, filters_json, calcs_json, group_json,
                     order_by_json, export_config, pagination_json, updated_at)
VALUES (
  'rpt_cust_month_flow', '客户月度黑白件汇总表',
  'U9黑件杂收(BF003)入库与白件出货(BF004)出库，按月×客户汇总吨数与笔数',
  'ds_u9c_misc_rcv_l', true,
  $fn$
[
  {"key": "month", "label": "月份", "from": "month"},
  {"key": "customer", "label": "客户", "from": "customer"},
  {"key": "in_qty", "label": "黑件入库量(吨)", "from": "in_qty"},
  {"key": "in_cnt", "label": "入库笔数", "from": "in_cnt"},
  {"key": "out_qty", "label": "白件出库量(吨)", "from": "out_qty"},
  {"key": "out_cnt", "label": "出库笔数", "from": "out_cnt"},
  {"key": "diff", "label": "出-入差额(吨)", "from": "diff"}
]
$fn$,
  $ff$
[
  {"key": "month", "label": "月份", "op": "month", "options": []},
  {"key": "customer", "label": "客户", "op": "like", "options": []}
]
$ff$,
  $cn$
[
  {
    "key": "diff",
    "label": "出-入差额(吨)",
    "kind": "formula",
    "formula": "out_qty - in_qty",
    "precision": 6
  }
]
$cn$,
  $gn$
{
  "by": ["month", "customer"],
  "aggs": [
    {"key": "in_qty", "fn": "sum", "field": "in_qty"},
    {"key": "in_cnt", "fn": "count", "field": "in_qty"},
    {"key": "out_qty", "fn": "sum", "field": "out_qty"},
    {"key": "out_cnt", "fn": "count", "field": "out_qty"}
  ]
}
$gn$,
  '[{"field":"month","dir":"desc"},{"field":"customer","dir":"asc"}]',
  '{}',
  $pn$
{
  "defaultPageSize": 50,
  "pageSizeOptions": [20, 50, 100, 200],
  "showJump": true,
  "pageParam": "page",
  "pageSizeParam": "pageSize",
  "pageBase": 1
}
$pn$,
  now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, description = EXCLUDED.description,
  root_dataset = EXCLUDED.root_dataset, enabled = EXCLUDED.enabled,
  fields_json = EXCLUDED.fields_json, filters_json = EXCLUDED.filters_json,
  calcs_json = EXCLUDED.calcs_json, group_json = EXCLUDED.group_json,
  order_by_json = EXCLUDED.order_by_json, pagination_json = EXCLUDED.pagination_json,
  updated_at = now();

-- 同步配置：每日凌晨全量拉两侧行（list 模式 500 行/页，上限 20 页）
INSERT INTO sync_configs (dataset_code, enabled, mode, page_size, max_pages, notes,
                          schedule_type, schedule_time, updated_at)
VALUES
  ('ds_u9c_misc_rcv_l', TRUE, 'list', 500, 20, 'U9黑件杂收BF003全量',
   'daily', '02:00', now()),
  ('ds_u9c_ship_l', TRUE, 'list', 500, 20, 'U9白件出货BF004全量',
   'daily', '02:10', now())
ON CONFLICT (dataset_code) DO UPDATE SET
  enabled = EXCLUDED.enabled, mode = EXCLUDED.mode, page_size = EXCLUDED.page_size,
  max_pages = EXCLUDED.max_pages, notes = EXCLUDED.notes,
  schedule_type = EXCLUDED.schedule_type, schedule_time = EXCLUDED.schedule_time,
  updated_at = now();
