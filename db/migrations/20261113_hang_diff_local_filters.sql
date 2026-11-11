-- 修复 挂次白件重量差异表 在本地数据模式下筛选失效：
-- 筛选键是上游参数名（endImmersionTime / work_status），本地模式投影行里没有同名键会被内存过滤静默跳过。
-- 通过 localField 把筛选落到本地字段：月份→work_date（与列表显示的排班日期一致）、挂次状态→work_status（状态码）。
-- 另：op=month 的筛选在引擎里按月粒度含端点比较，避免月末当天被排除。

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('number', 'ds_hang', 'productionStatus', 'work_status', NULL, 25)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

UPDATE reports SET
  fields_json = $fn$
[
  {"key": "hang_no", "label": "挂次编号", "from": "ds_hang.hang_no"},
  {"key": "work_date", "label": "排班日期", "from": "ds_hang.work_date"},
  {"key": "prod_status", "label": "生产状态", "from": "ds_hang.prod_status"},
  {"key": "customer_name", "label": "客户", "from": "ds_hang.customer_name"},
  {"key": "product_name", "label": "产品名称", "from": "ds_hang.product_name"},
  {"key": "item_code", "label": "物料编码", "from": "ds_hang.item_code"},
  {"key": "black_weight", "label": "MES黑件重量(kg)", "from": "ds_hang.black_weight"},
  {"key": "white_weight", "label": "MES白件重量(kg)", "from": "ds_hang.white_weight"},
  {"key": "u9_complete_qty", "label": "U9完工数量(吨)", "from": "ds_u9c_complete.complete_qty"},
  {"key": "u9_rcv_qty", "label": "U9入库数量(吨)", "from": "ds_u9c_complete.rcv_qty"},
  {"key": "u9_complete_kg", "label": "U9完工(kg)", "from": "u9_complete_kg"},
  {"key": "u9_rcv_kg", "label": "U9入库(kg)", "from": "u9_rcv_kg"},
  {"key": "diff_complete_kg", "label": "与完工差异(kg)", "from": "diff_complete_kg"},
  {"key": "diff_rcv_kg", "label": "与入库差异(kg)", "from": "diff_rcv_kg"},
  {"key": "u9_status", "label": "U9报告", "from": "u9_status"},
  {"key": "diff_flag", "label": "是否一致", "from": "diff_flag"},
  {"key": "work_status", "label": "挂次状态", "from": "ds_hang.work_status", "hidden": true}
]
$fn$,
  filters_json = $ff$
[
  {
    "key": "endImmersionTime",
    "label": "月份",
    "op": "month",
    "localField": "work_date",
    "options": []
  },
  {
    "key": "work_status",
    "label": "挂次状态",
    "op": "select",
    "localField": "work_status",
    "options": [
      {"value": "12", "label": "白件已称重"},
      {"value": "1", "label": "未酸洗"},
      {"value": "2", "label": "已酸洗"},
      {"value": "3", "label": "已称重"},
      {"value": "4", "label": "已浸锌"},
      {"value": "5", "label": "已完成"}
    ]
  },
  {
    "key": "diff_flag",
    "label": "是否一致",
    "op": "select",
    "options": [
      {"value": "一致", "label": "一致"},
      {"value": "不一致", "label": "不一致"}
    ]
  }
]
$ff$,
  updated_at = now()
WHERE code = 'rpt_white_weight_diff';
