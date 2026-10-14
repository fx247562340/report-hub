-- CRM 销售订单主表（BladeX OAuth: Basic saber:saber_secret + password grant）
-- 密码为 MD5；生产可改为从部署配置覆盖

INSERT INTO data_sources (code, name, auth_type, base_url, auth_config, env, timeout_ms, updated_at)
VALUES ('crm', 'CRM销售订单', 'session', 'https://crm.btbfaf.com', $ac$
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
  "reloginOnStatus": [
    401,
    403
  ],
  "success": {
    "type": "not_empty",
    "path": "access_token"
  }
}
$ac$, 'prod', 30000, now())
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, auth_type=EXCLUDED.auth_type,
  base_url=EXCLUDED.base_url, auth_config=EXCLUDED.auth_config, timeout_ms=EXCLUDED.timeout_ms, updated_at=now();

INSERT INTO api_endpoints (code, name, data_source_code, method, path, query_template, body_template, headers_template, list_path, total_path, pagination, updated_at)
VALUES ('crm_so_list', 'CRM销售订单列表', 'crm', 'GET', '/api/blade-contractOrder/contractOrder/list',
  '{"current": "{{page}}", "size": "{{pageSize}}"}', '', '{}', 'data.records', 'data.total', 'page', now())
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, data_source_code=EXCLUDED.data_source_code,
  method=EXCLUDED.method, path=EXCLUDED.path, query_template=EXCLUDED.query_template,
  list_path=EXCLUDED.list_path, total_path=EXCLUDED.total_path, pagination=EXCLUDED.pagination, updated_at=now();

INSERT INTO datasets (code, endpoint_code, fetch_hint, name, updated_at)
VALUES ('ds_crm_so', 'crm_so_list', 'list', 'CRM销售订单', now())
ON CONFLICT (code) DO UPDATE SET endpoint_code=EXCLUDED.endpoint_code, name=EXCLUDED.name, updated_at=now();

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','orderNo','order_no',0) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','customerName','customer_name',1) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','orderCommandNo','order_command_no',2) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','contractCode','contract_code',3) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','productType','product_type',4) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','deliveryDate','delivery_date',5) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','priority','priority',6) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','salesOrderStatus','sales_order_status',7) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','processStatus','process_status',8) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','salesmanName','salesman_name',9) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','createTime','create_time',10) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','remark','remark',11) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;
INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, sort) VALUES ('string','ds_crm_so','id','source_id',12) ON CONFLICT (dataset_code, target_field) DO UPDATE SET source_path=EXCLUDED.source_path, data_type=EXCLUDED.data_type;

INSERT INTO reports (code, name, description, root_dataset, enabled, fields_json, filters_json, calcs_json, order_by_json, export_config, pagination_json, updated_at)
VALUES ('rpt_crm_so', 'CRM销售订单', 'CRM 合同/销售订单列表（主表，待接 ERP 开工 / 军品 MES 流动卡）', 'ds_crm_so', true,
  $fn$
[
  {
    "key": "order_no",
    "label": "销售订单号",
    "from": "ds_crm_so.order_no"
  },
  {
    "key": "customer_name",
    "label": "客户名称",
    "from": "ds_crm_so.customer_name"
  },
  {
    "key": "order_command_no",
    "label": "订单指令号",
    "from": "ds_crm_so.order_command_no"
  },
  {
    "key": "contract_code",
    "label": "合同号",
    "from": "ds_crm_so.contract_code"
  },
  {
    "key": "product_type",
    "label": "产品类型",
    "from": "ds_crm_so.product_type"
  },
  {
    "key": "delivery_date",
    "label": "交货日期",
    "from": "ds_crm_so.delivery_date"
  },
  {
    "key": "priority",
    "label": "优先级",
    "from": "ds_crm_so.priority"
  },
  {
    "key": "sales_order_status",
    "label": "订单状态",
    "from": "ds_crm_so.sales_order_status"
  },
  {
    "key": "process_status",
    "label": "流程状态",
    "from": "ds_crm_so.process_status"
  },
  {
    "key": "salesman_name",
    "label": "业务员",
    "from": "ds_crm_so.salesman_name"
  },
  {
    "key": "create_time",
    "label": "创建时间",
    "from": "ds_crm_so.create_time"
  },
  {
    "key": "remark",
    "label": "备注",
    "from": "ds_crm_so.remark"
  }
]
$fn$,
  $ff$
[
  {
    "key": "orderNo",
    "label": "订单号",
    "op": "contains",
    "options": []
  },
  {
    "key": "customerName",
    "label": "客户",
    "op": "contains",
    "options": []
  }
]
$ff$,
  '[]', '[{"field":"create_time","dir":"desc"}]', '{}',
  $pn$
{
  "defaultPageSize": 20,
  "pageSizeOptions": [
    10,
    20,
    50,
    100
  ],
  "showJump": true,
  "pageParam": "current",
  "pageSizeParam": "size",
  "pageBase": 1,
  "totalPath": "data.total"
}
$pn$,
  now())
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description,
  root_dataset=EXCLUDED.root_dataset, enabled=EXCLUDED.enabled, fields_json=EXCLUDED.fields_json,
  filters_json=EXCLUDED.filters_json, order_by_json=EXCLUDED.order_by_json,
  pagination_json=EXCLUDED.pagination_json, updated_at=now();
