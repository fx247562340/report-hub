-- 挂次差异表的 MES黑件重量 取错字段：hangWeightHei（镀锌前实测）要到「准备镀锌」才有值，
-- 未酸洗/酸洗中的挂次一直是 0。始终取 oldHeiWeight（酸洗前黑件重量）——挂次建立即有值。

UPDATE reports SET
  fields_json = $fn$
[
  {"key": "hang_no", "label": "挂次编号", "from": "ds_hang.hang_no"},
  {"key": "work_date", "label": "排班日期", "from": "ds_hang.work_date"},
  {"key": "prod_status", "label": "生产状态", "from": "ds_hang.prod_status"},
  {"key": "customer_name", "label": "客户", "from": "ds_hang.customer_name"},
  {"key": "product_name", "label": "产品名称", "from": "ds_hang.product_name"},
  {"key": "item_code", "label": "物料编码", "from": "ds_hang.item_code"},
  {"key": "mes_black_weight", "label": "MES黑件重量(kg)", "from": "ds_hang.acid_black_weight"},
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
  calcs_json = $cn$
[
  {
    "key": "u9_complete_kg",
    "label": "U9完工(kg)",
    "kind": "formula",
    "formula": "u9_complete_qty * 1000",
    "precision": 3
  },
  {
    "key": "u9_rcv_kg",
    "label": "U9入库(kg)",
    "kind": "formula",
    "formula": "u9_rcv_qty * 1000",
    "precision": 3
  },
  {
    "key": "diff_complete_kg",
    "label": "与完工差异(kg)",
    "kind": "formula",
    "formula": "white_weight - u9_complete_kg",
    "precision": 3
  },
  {
    "key": "diff_rcv_kg",
    "label": "与入库差异(kg)",
    "kind": "formula",
    "formula": "white_weight - u9_rcv_kg",
    "precision": 3
  },
  {
    "key": "u9_status",
    "label": "U9报告",
    "kind": "condition",
    "when": "u9_complete_kg > 0",
    "then": "有",
    "else": "无"
  },
  {
    "key": "diff_flag",
    "label": "是否一致",
    "kind": "condition",
    "when": "u9_complete_kg > 0 && abs(white_weight - u9_complete_kg) <= 0.05",
    "then": "一致",
    "else": "不一致"
  }
]
$cn$,
  updated_at = now()
WHERE code = 'rpt_white_weight_diff';
