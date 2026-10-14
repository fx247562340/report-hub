-- 端木 MES 修正：领号任务(工单号) + 流动卡张数
-- 领号任务：GET /api/blade-erp/order/list?code={{销售订单号}}
--   productionOrderNo = 工单号（与 U9 MO 对齐）
-- 流动卡：GET /api/blade-mes/plan/page?orderCode={{销售订单号}}
--   张数用关联 __count；是否生成看张数/卡号

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, query_template, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'mes_erp_order_list', 'MES领号任务', 'mes', 'GET', '/api/blade-erp/order/list',
  '{"code": "{{key}}", "current": "1", "size": "200"}',
  '',
  '{}',
  'data.records', 'data.total', 'page', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, query_template = EXCLUDED.query_template,
  body_template = EXCLUDED.body_template, headers_template = EXCLUDED.headers_template,
  list_path = EXCLUDED.list_path, total_path = EXCLUDED.total_path,
  pagination = EXCLUDED.pagination, updated_at = now();

INSERT INTO datasets (code, name, endpoint_code, fetch_hint, updated_at)
VALUES ('ds_mes_erp_order', 'MES领号任务', 'mes_erp_order_list', 'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code,
  fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_mes_erp_order', 'match_key', 'join_key', 'lower', 10),
  ('string', 'ds_mes_erp_order', 'code', 'mes_order_code', NULL, 20),
  ('string', 'ds_mes_erp_order', 'productionOrderNo', 'mes_mo_no', NULL, 30),
  ('string', 'ds_mes_erp_order', 'customerName', 'mes_customer_name', NULL, 40),
  ('string', 'ds_mes_erp_order', 'productMsg', 'mes_product_msg', NULL, 50),
  ('string', 'ds_mes_erp_order', 'completePartNo', 'mes_complete_part_no', NULL, 60),
  ('string', 'ds_mes_erp_order', 'u9Code', 'mes_u9_code', NULL, 70),
  ('string', 'ds_mes_erp_order', 'planStartDate', 'mes_plan_start', NULL, 80),
  ('string', 'ds_mes_erp_order', 'planEndDate', 'mes_plan_end', NULL, 90),
  ('string', 'ds_mes_erp_order', 'realStartDate', 'mes_real_start', NULL, 100),
  ('number', 'ds_mes_erp_order', 'status', 'mes_order_status', NULL, 110),
  ('number', 'ds_mes_erp_order', 'detailPlanNumTotal', 'mes_plan_num_total', NULL, 120),
  ('bool', 'ds_mes_erp_order', 'productionCompleted', 'mes_production_completed', NULL, 130)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 按销售订单拉领号任务，再按工单号挂到 U9 生产订单行上（N-1）
INSERT INTO relations (
  code, report_code, left_dataset, right_dataset, join_type, cardinality,
  on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at
) VALUES (
  'rel_mo_mes_erp_order', 'rpt_crm_so', 'ds_u9c_mo_by_so', 'ds_mes_erp_order',
  'left', 'N-1',
  '[{"leftField":"mo_no","rightField":"mes_mo_no"}]',
  'child_lookup',
  '{"endpoint":"mes_erp_order_list","detailEndpoint":"mes_erp_order_list","keyField":"join_key","pathParam":"id","keyParam":"key","concurrency":6}',
  'flat', 2, now()
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

-- 流动卡：按销售订单查，N-1 取首卡 + __count 张数
UPDATE relations SET
  fetch_config = '{"endpoint":"mes_plan_page","detailEndpoint":"mes_plan_page","keyField":"join_key","pathParam":"id","keyParam":"key","concurrency":6}',
  sort = 3,
  updated_at = now()
WHERE code = 'rel_crm_so_mes_card';

UPDATE reports SET
  description = 'CRM 销售订单 → 用友/MES 工单（是否开工）→ 端木 MES 流动卡（是否生成、张数）。一单多工单摊平；流动卡张数按订单统计，卡号展示首张。',
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
  {"key": "mes_mo_no", "from": "ds_mes_erp_order.mes_mo_no", "label": "MES工单号"},
  {"key": "mo_product_qty", "from": "ds_u9c_mo_by_so.mo_product_qty", "label": "生产数量"},
  {"key": "mo_start", "from": "ds_u9c_mo_by_so.mo_start", "label": "计划开工"},
  {"key": "mo_complete", "from": "ds_u9c_mo_by_so.mo_complete", "label": "计划完工"},
  {"key": "mes_product_msg", "from": "ds_mes_erp_order.mes_product_msg", "label": "产品说明"},
  {"key": "mes_complete_part_no", "from": "ds_mes_erp_order.mes_complete_part_no", "label": "完工件号"},
  {"key": "mes_plan_start", "from": "ds_mes_erp_order.mes_plan_start", "label": "领号计划开始"},
  {"key": "mes_real_start", "from": "ds_mes_erp_order.mes_real_start", "label": "领号实际开始"},
  {"key": "is_flowcard", "from": "is_flowcard", "label": "是否生成流动卡", "calc": "condition"},
  {"key": "flow_card_count", "from": "ds_mes_plan.__count", "label": "流动卡张数"},
  {"key": "flow_card_no", "from": "ds_mes_plan.flow_card_no", "label": "流动卡号(首张)"},
  {"key": "flow_card_part", "from": "ds_mes_plan.flow_card_part", "label": "零件名称"},
  {"key": "flow_card_create_time", "from": "ds_mes_plan.flow_card_create_time", "label": "流动卡生成时间"},
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
  },
  {
    "key": "is_flowcard",
    "label": "是否生成流动卡",
    "kind": "condition",
    "formula": "",
    "when": "flow_card_count > 0",
    "then": "已生成",
    "else": "未生成",
    "precision": 3
  }
]
$cn$,
  updated_at = now()
WHERE code = 'rpt_crm_so';
