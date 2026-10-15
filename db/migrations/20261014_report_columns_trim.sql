-- 列表只保留 11 列
UPDATE reports SET
  fields_json = $fn$
[
  {"key": "order_no", "from": "ds_crm_so.order_no", "label": "销售订单号"},
  {"key": "customer_name", "from": "ds_crm_so.customer_name", "label": "客户名称"},
  {"key": "order_command_no", "from": "ds_crm_so.order_command_no", "label": "项目号/令号"},
  {"key": "contract_code", "from": "ds_crm_so.contract_code", "label": "合同号"},
  {"key": "process_status", "from": "ds_crm_so.process_status", "label": "流程状态"},
  {"key": "salesman_name", "from": "ds_crm_so.salesman_name", "label": "业务员"},
  {"key": "is_started", "from": "is_started", "label": "是否开工", "calc": "condition"},
  {"key": "mo_count", "from": "ds_u9c_mo_by_so.__count", "label": "工单数"},
  {"key": "mes_order_count", "from": "ds_mes_erp_order.__count", "label": "令号任务数"},
  {"key": "is_flowcard", "from": "is_flowcard", "label": "是否生成流动卡", "calc": "condition"},
  {"key": "flow_card_count", "from": "ds_mes_plan.__count", "label": "流动卡张数"}
]
$fn$,
  updated_at = now()
WHERE code = 'rpt_crm_so';
