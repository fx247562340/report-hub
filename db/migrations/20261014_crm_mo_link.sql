-- CRM 销售订单 → 用友生产订单（1:N）
-- 关联键两侧 lower 对齐：CRM order_no → U9 match_key（U9 SQL 字面量会返回小写）
-- 1-N + flat：一单多工单时主表字段重复多行，Excel 友好

UPDATE api_endpoints SET
  name = 'U9C按销售订单查生产订单',
  method = 'POST',
  path = '/webapi/QueryCommon/QueryInfoBySql',
  query_template = '{}',
  list_path = 'Data',
  total_path = NULL,
  pagination = 'none',
  body_template = $sql$
{"SqlString": "SELECT DISTINCT TOP 500 '{{key}}' AS match_key, m.DocNo AS mo_no, r.SrcDoc_SrcDocNo AS so_no, COALESCE(NULLIF(l.ContractNo,''), NULLIF(l.DescFlexField_PubDescSeg1,'')) AS contract_no, l.ContractLineNo AS contract_line, m.ProductQty AS product_qty, m.StartDate AS mo_start, m.CompleteDate AS mo_complete FROM MO_MO m JOIN MO_MOSourceDocRelation r ON r.MO = m.ID LEFT JOIN SM_SOLine l ON l.ID = r.SrcDoc_SrcDocLine_EntityID WHERE r.SrcDoc_SrcDocNo = '{{key}}' OR l.ContractNo = '{{key}}' OR l.DescFlexField_PubDescSeg1 = '{{key}}'"}
$sql$,
  updated_at = now()
WHERE code = 'u9c_mo_by_so';

-- CRM 侧关联键（小写订单号）
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES ('string', 'ds_crm_so', 'orderNo', 'join_key', 'lower', 90)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 子数据集：按销售订单/合同反查到的生产订单
INSERT INTO datasets (code, name, endpoint_code, fetch_hint, updated_at)
VALUES ('ds_u9c_mo_by_so', 'U9C生产订单(按销售订单)', 'u9c_mo_by_so', 'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code, fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_u9c_mo_by_so', 'match_key', 'join_key', 'lower', 10),
  ('string', 'ds_u9c_mo_by_so', 'mo_no', 'mo_no', NULL, 20),
  ('string', 'ds_u9c_mo_by_so', 'so_no', 'so_no', NULL, 30),
  ('string', 'ds_u9c_mo_by_so', 'contract_no', 'mo_contract_no', NULL, 40),
  ('number', 'ds_u9c_mo_by_so', 'contract_line', 'mo_contract_line', NULL, 50),
  ('number', 'ds_u9c_mo_by_so', 'product_qty', 'mo_product_qty', NULL, 60),
  ('string', 'ds_u9c_mo_by_so', 'mo_start', 'mo_start', NULL, 70),
  ('string', 'ds_u9c_mo_by_so', 'mo_complete', 'mo_complete', NULL, 80)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 1:N 关联：一张销售订单可对应多张生产订单
INSERT INTO relations (
  code, report_code, left_dataset, right_dataset, join_type, cardinality,
  on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at
) VALUES (
  'rel_crm_so_mo', 'rpt_crm_so', 'ds_crm_so', 'ds_u9c_mo_by_so',
  'left', '1-N',
  '[{"leftField":"join_key","rightField":"join_key"}]',
  'child_lookup',
  '{"endpoint":"u9c_mo_by_so","detailEndpoint":"u9c_mo_by_so","pathParam":"id","keyParam":"key","concurrency":8}',
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

-- 报表字段 + 是否开工
UPDATE reports SET
  name = 'CRM销售订单',
  description = 'CRM 销售订单主表 → 用友生产订单（1:N 摊平）。一单多工单时订单字段重复；无生产订单=未开工。',
  fields_json = $fn$
[
  {"key": "order_no", "from": "ds_crm_so.order_no", "label": "销售订单号"},
  {"key": "customer_name", "from": "ds_crm_so.customer_name", "label": "客户名称"},
  {"key": "order_command_no", "from": "ds_crm_so.order_command_no", "label": "订单指令号"},
  {"key": "contract_code", "from": "ds_crm_so.contract_code", "label": "合同号"},
  {"key": "product_type", "from": "ds_crm_so.product_type", "label": "产品类型"},
  {"key": "delivery_date", "from": "ds_crm_so.delivery_date", "label": "交货日期"},
  {"key": "priority", "from": "ds_crm_so.priority", "label": "优先级"},
  {"key": "sales_order_status", "from": "ds_crm_so.sales_order_status", "label": "订单状态"},
  {"key": "process_status", "from": "ds_crm_so.process_status", "label": "流程状态"},
  {"key": "salesman_name", "from": "ds_crm_so.salesman_name", "label": "业务员"},
  {"key": "is_started", "from": "is_started", "label": "是否开工", "calc": "condition"},
  {"key": "mo_no", "from": "ds_u9c_mo_by_so.mo_no", "label": "生产订单号"},
  {"key": "mo_product_qty", "from": "ds_u9c_mo_by_so.mo_product_qty", "label": "生产数量"},
  {"key": "mo_start", "from": "ds_u9c_mo_by_so.mo_start", "label": "计划开工"},
  {"key": "mo_complete", "from": "ds_u9c_mo_by_so.mo_complete", "label": "计划完工"},
  {"key": "mo_so_no", "from": "ds_u9c_mo_by_so.so_no", "label": "U9销售订单号"},
  {"key": "create_time", "from": "ds_crm_so.create_time", "label": "创建时间"},
  {"key": "remark", "from": "ds_crm_so.remark", "label": "备注"}
]
$fn$,
  calcs_json = $cn$
[
  {
    "key": "is_started",
    "label": "是否开工",
    "kind": "condition",
    "formula": "",
    "when": "mo_no",
    "then": "已开工",
    "else": "未开工",
    "precision": 3
  }
]
$cn$,
  filters_json = $ff$
[
  {"key": "orderNo", "label": "订单号", "op": "eq", "options": []},
  {"key": "customerName", "label": "客户", "op": "contains", "options": []},
  {"key": "is_started", "label": "是否开工", "op": "eq", "options": ["已开工", "未开工"]}
]
$ff$,
  updated_at = now()
WHERE code = 'rpt_crm_so';

-- 上游筛选参数（BladeX list 支持 orderNo / customerName）
UPDATE api_endpoints SET
  query_template = '{"current": "{{page}}", "size": "{{pageSize}}", "orderNo": "{{filter.orderNo}}", "customerName": "{{filter.customerName}}"}',
  updated_at = now()
WHERE code = 'crm_so_list';
