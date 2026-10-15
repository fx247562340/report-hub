-- 性能：MES 子查询 size=1，条数用接口 total；并发提高
-- 列表一单一行；去掉明细弹窗交互

UPDATE api_endpoints SET
  query_template = '{"orderCode": "{{key}}", "current": "1", "size": "1"}',
  updated_at = now()
WHERE code = 'mes_plan_page';

UPDATE api_endpoints SET
  query_template = '{"code": "{{key}}", "current": "1", "size": "1"}',
  updated_at = now()
WHERE code = 'mes_erp_order_list';

UPDATE relations SET fetch_config = replace(replace(fetch_config,
  '"concurrency":8', '"concurrency":16'), '"concurrency":6', '"concurrency":12'), updated_at = now()
WHERE report_code = 'rpt_crm_so';
