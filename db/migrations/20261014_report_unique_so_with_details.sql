-- 列表一单一行：工单/领号/流动卡只显示条数，点击弹窗看明细
-- 引擎需支持：关联 __count、child_lookup keyField、POST /reports/{code}/children

UPDATE relations SET
  cardinality = 'N-1',
  expand_mode = 'flat',
  on_fields = '[{"leftField":"join_key","rightField":"join_key"}]',
  fetch_config = '{"endpoint":"u9c_mo_by_so","detailEndpoint":"u9c_mo_by_so","keyField":"join_key","pathParam":"id","keyParam":"key","concurrency":8,"detailColumns":[{"key":"mo_no","label":"生产订单号"},{"key":"so_no","label":"U9销售订单号"},{"key":"mo_contract_no","label":"合同号"},{"key":"mo_product_qty","label":"生产数量"},{"key":"mo_start","label":"计划开工"},{"key":"mo_complete","label":"计划完工"}]}',
  updated_at = now()
WHERE code = 'rel_crm_so_mo';

UPDATE relations SET
  fetch_config = '{"endpoint":"mes_erp_order_list","detailEndpoint":"mes_erp_order_list","keyField":"cmd_key","pathParam":"id","keyParam":"key","concurrency":6,"detailColumns":[{"key":"mes_mo_no","label":"令号/工单号"},{"key":"mes_product_msg","label":"产品说明"},{"key":"mes_complete_part_no","label":"完工件号"},{"key":"mes_plan_start","label":"计划开始"},{"key":"mes_real_start","label":"实际开始"},{"key":"mes_plan_num_total","label":"计划数"}]}',
  updated_at = now()
WHERE code = 'rel_mo_mes_erp_order';

UPDATE relations SET
  fetch_config = '{"endpoint":"mes_plan_page","detailEndpoint":"mes_plan_page","keyField":"cmd_key","pathParam":"id","keyParam":"key","concurrency":6,"detailColumns":[{"key":"flow_card_no","label":"流动卡号"},{"key":"flow_card_bar","label":"条码"},{"key":"flow_card_part","label":"零件名称"},{"key":"flow_card_part_code","label":"零件编码"},{"key":"flow_card_num","label":"数量"},{"key":"flow_card_plan_date","label":"计划日期"},{"key":"flow_card_create_time","label":"生成时间"}]}',
  updated_at = now()
WHERE code = 'rel_crm_so_mes_card';

UPDATE reports SET
  description = 'CRM 销售订单主表（一单一行）。工单/领号/流动卡只显示条数，点击条数或明细查看列表。',
  fields_json = $fn$
[
  {"key": "order_no", "from": "ds_crm_so.order_no", "label": "销售订单号"},
  {"key": "customer_name", "from": "ds_crm_so.customer_name", "label": "客户名称"},
  {"key": "order_command_no", "from": "ds_crm_so.order_command_no", "label": "项目号/领号"},
  {"key": "contract_code", "from": "ds_crm_so.contract_code", "label": "合同号"},
  {"key": "product_type", "from": "ds_crm_so.product_type", "label": "产品类型"},
  {"key": "delivery_date", "from": "ds_crm_so.delivery_date", "label": "交货日期"},
  {"key": "priority", "from": "ds_crm_so.priority", "label": "优先级"},
  {"key": "sales_order_status", "from": "ds_crm_so.sales_order_status", "label": "订单状态"},
  {"key": "process_status", "from": "ds_crm_so.process_status", "label": "流程状态"},
  {"key": "salesman_name", "from": "ds_crm_so.salesman_name", "label": "业务员"},
  {"key": "is_started", "from": "is_started", "label": "是否开工", "calc": "condition"},
  {"key": "mo_count", "from": "ds_u9c_mo_by_so.__count", "label": "工单数", "link": "mo"},
  {"key": "mes_order_count", "from": "ds_mes_erp_order.__count", "label": "领号任务数", "link": "mes_erp_order"},
  {"key": "is_flowcard", "from": "is_flowcard", "label": "是否生成流动卡", "calc": "condition"},
  {"key": "flow_card_count", "from": "ds_mes_plan.__count", "label": "流动卡张数", "link": "mes_plan"},
  {"key": "mes_mo_no", "from": "ds_mes_erp_order.mes_mo_no", "label": "MES令号"},
  {"key": "mo_no", "from": "ds_u9c_mo_by_so.mo_no", "label": "生产订单号"},
  {"key": "flow_card_no", "from": "ds_mes_plan.flow_card_no", "label": "流动卡号(首张)"},
  {"key": "create_time", "from": "ds_crm_so.create_time", "label": "创建时间"},
  {"key": "remark", "from": "ds_crm_so.remark", "label": "备注"}
]
$fn$,
  calcs_json = $cn$
[
  {"key": "is_started", "label": "是否开工", "kind": "condition", "formula": "", "when": "mo_count > 0", "then": "已开工", "else": "未开工", "precision": 3},
  {"key": "is_flowcard", "label": "是否生成流动卡", "kind": "condition", "formula": "", "when": "flow_card_count > 0", "then": "已生成", "else": "未生成", "precision": 3}
]
$cn$,
  updated_at = now()
WHERE code = 'rpt_crm_so';
