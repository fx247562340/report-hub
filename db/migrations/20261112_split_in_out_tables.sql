-- 拆成两张表：白件出库表（项目号-客户-料品）与黑件入库表（客户-料品）
-- 黑件杂收单在 U9 上有 Project.Code 字段但基本没录（1196 行里 1195 行为空），故不带项目号
-- fieldsJson 里 "merge": true 的列在界面与 Excel 导出时纵向合并同值单元格；
-- "hidden": true 的列（月份）不显示/不导出，但仍参与筛选、分组与合并分层

DELETE FROM relations WHERE report_code = 'rpt_cust_month_flow';
DELETE FROM reports WHERE code = 'rpt_cust_month_flow';

INSERT INTO reports (code, name, description, root_dataset, enabled,
                     fields_json, filters_json, calcs_json, group_json,
                     order_by_json, export_config, pagination_json, updated_at)
VALUES (
  'rpt_cust_month_out', '客户月度白件出库表',
  'U9白件出货(BF004)按月×项目号×客户×料品汇总出库吨数与笔数',
  'ds_u9c_ship_l', true,
  $fn$
[
  {"key": "month", "label": "月份", "from": "month", "hidden": true, "merge": true},
  {"key": "project_no", "label": "项目号", "from": "project_no", "merge": true},
  {"key": "customer", "label": "客户", "from": "customer", "merge": true},
  {"key": "item_code", "label": "料号", "from": "item_code"},
  {"key": "product", "label": "料品", "from": "product"},
  {"key": "out_qty", "label": "出库量(吨)", "from": "out_qty"},
  {"key": "out_cnt", "label": "出库笔数", "from": "out_cnt"}
]
$fn$,
  $ff$
[
  {"key": "month", "label": "月份", "op": "month", "min": "2026-06", "options": []},
  {"key": "project_no", "label": "项目号", "op": "like", "options": []},
  {"key": "customer", "label": "客户", "op": "like", "options": []},
  {"key": "product", "label": "料品", "op": "like", "options": []}
]
$ff$,
  '[]',
  $gn$
{
  "by": ["month", "project_no", "customer", "product", "item_code"],
  "aggs": [
    {"key": "out_qty", "fn": "sum", "field": "out_qty"},
    {"key": "out_cnt", "fn": "count", "field": "out_qty"}
  ]
}
$gn$,
  '[{"field":"month","dir":"desc"},{"field":"project_no","dir":"asc"},{"field":"customer","dir":"asc"},{"field":"product","dir":"asc"},{"field":"item_code","dir":"asc"}]',
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

INSERT INTO reports (code, name, description, root_dataset, enabled,
                     fields_json, filters_json, calcs_json, group_json,
                     order_by_json, export_config, pagination_json, updated_at)
VALUES (
  'rpt_cust_month_in', '客户月度黑件入库表',
  'U9黑件杂收(BF003)按月×客户×料品汇总入库吨数与笔数（杂收单项目号未录）',
  'ds_u9c_misc_rcv_l', true,
  $fn$
[
  {"key": "month", "label": "月份", "from": "month", "hidden": true, "merge": true},
  {"key": "customer", "label": "客户", "from": "customer", "merge": true},
  {"key": "item_code", "label": "料号", "from": "item_code"},
  {"key": "product", "label": "料品", "from": "product"},
  {"key": "in_qty", "label": "入库量(吨)", "from": "in_qty"},
  {"key": "in_cnt", "label": "入库笔数", "from": "in_cnt"}
]
$fn$,
  $ff$
[
  {"key": "month", "label": "月份", "op": "month", "min": "2026-06", "options": []},
  {"key": "customer", "label": "客户", "op": "like", "options": []},
  {"key": "product", "label": "料品", "op": "like", "options": []}
]
$ff$,
  '[]',
  $gn$
{
  "by": ["month", "customer", "product", "item_code"],
  "aggs": [
    {"key": "in_qty", "fn": "sum", "field": "in_qty"},
    {"key": "in_cnt", "fn": "count", "field": "in_qty"}
  ]
}
$gn$,
  '[{"field":"month","dir":"desc"},{"field":"customer","dir":"asc"},{"field":"product","dir":"asc"},{"field":"item_code","dir":"asc"}]',
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
