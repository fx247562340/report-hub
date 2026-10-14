-- 修正：MES 令号/流动卡按 CRM 项目号 orderCommandNo 查询（不是销售订单号）
-- 领号任务：GET /api/blade-erp/order/list?code={{orderCommandNo}}
-- 流动卡：  GET /api/blade-mes/plan/page?orderCode={{orderCommandNo}}
-- U9 生产订单仍按销售订单号 order_no 关联

-- CRM 项目号关联键
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES ('string', 'ds_crm_so', 'orderCommandNo', 'cmd_key', 'lower', 91)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 领号任务：按 cmd_key 拉，关联 cmd_key=match_key（无 U9 工单时也能出令号）
UPDATE relations SET
  on_fields = '[{"leftField":"cmd_key","rightField":"match_key"}]',
  cardinality = 'N-1',
  fetch_config = '{"endpoint":"mes_erp_order_list","detailEndpoint":"mes_erp_order_list","keyField":"cmd_key","pathParam":"id","keyParam":"key","concurrency":6}',
  updated_at = now()
WHERE code = 'rel_mo_mes_erp_order';

-- 流动卡：按 cmd_key 拉，关联 cmd_key=match_key
UPDATE relations SET
  on_fields = '[{"leftField":"cmd_key","rightField":"match_key"}]',
  fetch_config = '{"endpoint":"mes_plan_page","detailEndpoint":"mes_plan_page","keyField":"cmd_key","pathParam":"id","keyParam":"key","concurrency":6}',
  updated_at = now()
WHERE code = 'rel_crm_so_mes_card';

UPDATE reports SET
  description = 'CRM 销售订单 → 用友生产订单（是否开工）→ MES 领号/令号 + 流动卡。MES 侧按项目号 orderCommandNo 查询；流动卡张数按项目号统计。',
  updated_at = now()
WHERE code = 'rpt_crm_so';


UPDATE reports SET
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
  {"key": "mo_no", "from": "ds_u9c_mo_by_so.mo_no", "label": "生产订单号"},
  {"key": "mes_mo_no", "from": "ds_mes_erp_order.mes_mo_no", "label": "MES令号/工单号"},
  {"key": "mes_order_count", "from": "ds_mes_erp_order.__count", "label": "领号任务数"},
  {"key": "mo_product_qty", "from": "ds_u9c_mo_by_so.mo_product_qty", "label": "生产数量"},
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
  updated_at = now()
WHERE code = 'rpt_crm_so';
