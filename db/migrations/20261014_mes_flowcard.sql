-- 端木/军品 MES 流动卡：按销售订单号（orderCode）反查是否已生成流动卡
-- 接口示例：GET /api/blade-mes/plan/page?orderCode=SO20260928005&current=1&size=10
-- 认证与 CRM 相同（BladeX saber:saber_secret + password grant）
-- 说明：入参是销售订单号（可带 /零件名），不是 U9 工单号；返回 code 即流动卡号
-- 关联用 N-1（取首卡），避免与生产订单 1:N 展开做笛卡尔积

INSERT INTO data_sources (code, name, auth_type, base_url, auth_config, env, timeout_ms, updated_at)
VALUES (
  'mes', '端木MES', 'session', 'https://mes.btbfaf.com',
  $ac$
{
  "loginPath": "/api/blade-auth/oauth/token",
  "loginMethod": "POST",
  "loginContentType": "form",
  "loginHeaders": {
    "Authorization": "Basic c2FiZXI6c2FiZXJfc2VjcmV0"
  },
  "usernameField": "username",
  "passwordField": "password",
  "username": "af30045",
  "password": "e10adc3949ba59abbe56e057f20f883e",
  "fields": {
    "tenantId": "000000",
    "grant_type": "password",
    "scope": "all",
    "type": "account"
  },
  "token": {
    "responsePath": "access_token",
    "style": "blade",
    "name": "Blade-Auth"
  },
  "ttlSeconds": 600000,
  "reloginOnStatus": [401, 403],
  "success": {
    "type": "not_empty",
    "path": "access_token"
  }
}
$ac$,
  'prod', 30000, now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, auth_type = EXCLUDED.auth_type, base_url = EXCLUDED.base_url,
  auth_config = EXCLUDED.auth_config, timeout_ms = EXCLUDED.timeout_ms, updated_at = now();

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, query_template, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'mes_plan_page', 'MES流动卡/计划列表', 'mes', 'GET', '/api/blade-mes/plan/page',
  '{"orderCode": "{{key}}", "current": "1", "size": "200"}',
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
VALUES ('ds_mes_plan', 'MES流动卡', 'mes_plan_page', 'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code,
  fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_mes_plan', 'match_key', 'join_key', 'lower', 10),
  ('string', 'ds_mes_plan', 'code', 'flow_card_no', NULL, 20),
  ('string', 'ds_mes_plan', 'barCode', 'flow_card_bar', NULL, 30),
  ('string', 'ds_mes_plan', 'orderCode', 'flow_card_order_code', NULL, 40),
  ('string', 'ds_mes_plan', 'orderPartName', 'flow_card_part', NULL, 50),
  ('string', 'ds_mes_plan', 'orderPartCode', 'flow_card_part_code', NULL, 60),
  ('string', 'ds_mes_plan', 'u9code', 'flow_card_u9code', NULL, 70),
  ('string', 'ds_mes_plan', 'planDate', 'flow_card_plan_date', NULL, 80),
  ('string', 'ds_mes_plan', 'createTime', 'flow_card_create_time', NULL, 90),
  ('number', 'ds_mes_plan', 'printStatus', 'flow_card_print_status', NULL, 100),
  ('number', 'ds_mes_plan', 'num', 'flow_card_num', NULL, 110)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 与生产订单 1:N 并列时用 N-1 取首卡，避免行数相乘
INSERT INTO relations (
  code, report_code, left_dataset, right_dataset, join_type, cardinality,
  on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at
) VALUES (
  'rel_crm_so_mes_card', 'rpt_crm_so', 'ds_crm_so', 'ds_mes_plan',
  'left', 'N-1',
  '[{"leftField":"join_key","rightField":"join_key"}]',
  'child_lookup',
  '{"endpoint":"mes_plan_page","detailEndpoint":"mes_plan_page","pathParam":"id","keyParam":"key","concurrency":6}',
  'flat', 1, now()
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

UPDATE reports SET
  description = 'CRM 销售订单 → 用友生产订单（是否开工）→ 端木 MES 流动卡（是否已生成）。一单多工单/多卡时摊平；流动卡取首卡展示。',
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
  {"key": "is_flowcard", "from": "is_flowcard", "label": "是否生成流动卡", "calc": "condition"},
  {"key": "flow_card_no", "from": "ds_mes_plan.flow_card_no", "label": "流动卡号"},
  {"key": "flow_card_bar", "from": "ds_mes_plan.flow_card_bar", "label": "流动卡条码"},
  {"key": "flow_card_part", "from": "ds_mes_plan.flow_card_part", "label": "零件名称"},
  {"key": "flow_card_plan_date", "from": "ds_mes_plan.flow_card_plan_date", "label": "计划日期"},
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
    "when": "flow_card_no",
    "then": "已生成",
    "else": "未生成",
    "precision": 3
  }
]
$cn$,
  filters_json = $ff$
[
  {"key": "orderNo", "label": "订单号", "op": "eq", "options": []},
  {"key": "customerName", "label": "客户", "op": "contains", "options": []},
  {"key": "is_started", "label": "是否开工", "op": "eq", "options": ["已开工", "未开工"]},
  {"key": "is_flowcard", "label": "是否生成流动卡", "op": "eq", "options": ["已生成", "未生成"]}
]
$ff$,
  updated_at = now()
WHERE code = 'rpt_crm_so';
