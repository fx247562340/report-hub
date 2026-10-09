-- 挂次白件重量差异表：MES 白件重量 vs U9 完工/入库数量（U9 吨×1000）
-- 挂次编号 = U9 完工报告 DocNo

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort)
VALUES ('number', 'ds_u9c_complete', 'm_rcvQtyByWhUOM', 'rcv_qty', 20)
ON CONFLICT (dataset_code, target_field) DO NOTHING;

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort)
VALUES ('number', 'ds_u9c_complete', 'm_completeQtyWhUOM', 'complete_qty_wh', 21)
ON CONFLICT (dataset_code, target_field) DO NOTHING;

INSERT INTO relations (code, report_code, left_dataset, right_dataset, join_type, cardinality, on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at)
VALUES (
  'rel_wdiff_hang_u9c', 'rpt_white_weight_diff', 'ds_hang', 'ds_u9c_complete',
  'left', 'N-1',
  '[{"leftField":"hang_no","rightField":"doc_no"}]',
  'child_lookup',
  '{"endpoint":"u9c_complete_query","detailEndpoint":"u9c_complete_query","pathParam":"id","concurrency":8}',
  'flat', 0, now()
)
ON CONFLICT (code) DO UPDATE SET
  report_code = EXCLUDED.report_code,
  left_dataset = EXCLUDED.left_dataset,
  right_dataset = EXCLUDED.right_dataset,
  join_type = EXCLUDED.join_type,
  cardinality = EXCLUDED.cardinality,
  on_fields = EXCLUDED.on_fields,
  fetch_mode = EXCLUDED.fetch_mode,
  fetch_config = EXCLUDED.fetch_config,
  expand_mode = EXCLUDED.expand_mode,
  sort = EXCLUDED.sort,
  updated_at = now();

INSERT INTO reports (code, name, description, root_dataset, enabled, fields_json, filters_json, calcs_json, order_by_json, export_config, pagination_json, updated_at)
VALUES (
  'rpt_white_weight_diff',
  '挂次白件重量差异表',
  'MES挂次（白件已称重）× U9完工报告，按月对比白件重量与完工/入库数量（U9吨×1000）',
  'ds_hang',
  true,
  $fn$
[
  {
    "key": "hang_no",
    "label": "挂次编号",
    "from": "ds_hang.hang_no"
  },
  {
    "key": "work_date",
    "label": "排班日期",
    "from": "ds_hang.work_date"
  },
  {
    "key": "prod_status",
    "label": "生产状态",
    "from": "ds_hang.prod_status"
  },
  {
    "key": "customer_name",
    "label": "客户",
    "from": "ds_hang.customer_name"
  },
  {
    "key": "product_name",
    "label": "产品名称",
    "from": "ds_hang.product_name"
  },
  {
    "key": "item_code",
    "label": "物料编码",
    "from": "ds_hang.item_code"
  },
  {
    "key": "black_weight",
    "label": "MES黑件重量(kg)",
    "from": "ds_hang.black_weight"
  },
  {
    "key": "white_weight",
    "label": "MES白件重量(kg)",
    "from": "ds_hang.white_weight"
  },
  {
    "key": "u9_complete_qty",
    "label": "U9完工数量(吨)",
    "from": "ds_u9c_complete.complete_qty"
  },
  {
    "key": "u9_rcv_qty",
    "label": "U9入库数量(吨)",
    "from": "ds_u9c_complete.rcv_qty"
  },
  {
    "key": "u9_complete_kg",
    "label": "U9完工(kg)",
    "from": "u9_complete_kg"
  },
  {
    "key": "u9_rcv_kg",
    "label": "U9入库(kg)",
    "from": "u9_rcv_kg"
  },
  {
    "key": "diff_complete_kg",
    "label": "与完工差异(kg)",
    "from": "diff_complete_kg"
  },
  {
    "key": "diff_rcv_kg",
    "label": "与入库差异(kg)",
    "from": "diff_rcv_kg"
  },
  {
    "key": "u9_status",
    "label": "U9报告",
    "from": "u9_status"
  },
  {
    "key": "diff_flag",
    "label": "是否一致",
    "from": "diff_flag"
  }
]
$fn$,
  $ff$
[
  {
    "key": "endImmersionTime",
    "label": "月份",
    "op": "month",
    "options": []
  },
  {
    "key": "diff_flag",
    "label": "是否一致",
    "op": "select",
    "options": [
      {
        "value": "一致",
        "label": "一致"
      },
      {
        "value": "不一致",
        "label": "不一致"
      }
    ]
  }
]
$ff$,
  $cn$
[
  {
    "key": "u9_complete_kg",
    "label": "U9完工(kg)",
    "kind": "formula",
    "formula": "u9_complete_qty * 1000",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "u9_rcv_kg",
    "label": "U9入库(kg)",
    "kind": "formula",
    "formula": "u9_rcv_qty * 1000",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "diff_complete_kg",
    "label": "与完工差异(kg)",
    "kind": "formula",
    "formula": "white_weight - u9_complete_kg",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "diff_rcv_kg",
    "label": "与入库差异(kg)",
    "kind": "formula",
    "formula": "white_weight - u9_rcv_kg",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "u9_status",
    "label": "U9报告",
    "kind": "condition",
    "formula": "",
    "when": "u9_complete_kg > 0",
    "then": "有",
    "else": "无",
    "precision": 3
  },
  {
    "key": "diff_flag",
    "label": "是否一致",
    "kind": "condition",
    "formula": "",
    "when": "u9_complete_kg > 0 && abs(white_weight - u9_complete_kg) <= 0.05",
    "then": "一致",
    "else": "不一致",
    "precision": 3
  }
]
$cn$,
  '[{"field":"work_date","dir":"desc"}]',
  '{}',
  $pn$
{
  "defaultPageSize": 50,
  "pageSizeOptions": [
    20,
    50,
    100,
    200
  ],
  "showJump": true,
  "pageParam": "page",
  "pageSizeParam": "limit",
  "pageBase": 1,
  "totalPath": "count"
}
$pn$,
  now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  root_dataset = EXCLUDED.root_dataset,
  enabled = EXCLUDED.enabled,
  fields_json = EXCLUDED.fields_json,
  filters_json = EXCLUDED.filters_json,
  calcs_json = EXCLUDED.calcs_json,
  order_by_json = EXCLUDED.order_by_json,
  pagination_json = EXCLUDED.pagination_json,
  updated_at = now();
