-- U9 已下架 QueryInfoBySql（keys 枚举与 ds_u9c_so 详情都走的它），本地三个 keys 数据集凌晨被清空。
-- 改造：
--   ds_u9c_complete 键源 = CommonEntity 枚举完工单号（GC-2026%，约 4456 个）
--   ds_u9c_mo / ds_u9c_so 键源 = 父数据集本地行字段（complete 的 m_mO.m_code）
--   合同号/销售订单号直接映射自 MODoc 的 m_project（=2026AF-DX-xxx 合同号），ds_u9c_so 不再单独取数
--   键为空时引擎保留本地数据，不再清库

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, body_type, body_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'u9c_keys_complete', 'U9完工单号枚举(CommonEntity)', 'u9c_erp', 'POST',
  '/webapi/CommonEntity/Query', 'json', $bt$
{
  "EntityFullName": "UFIDA.U9.MO.Complete.CompleteRpt",
  "ReturnFields": ["DocNo"],
  "PageSize": {{pageSize}},
  "PageIndex": {{page}},
  "Filters": [
    {"Field": "DocNo", "Operator": "like", "Value": "GC-2026%"}
  ]
}
$bt$,
  '{}', 'Data', NULL, 'none', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, body_type = EXCLUDED.body_type,
  body_template = EXCLUDED.body_template, headers_template = EXCLUDED.headers_template,
  list_path = EXCLUDED.list_path, pagination = EXCLUDED.pagination, updated_at = now();

UPDATE sync_configs SET
  keys_sql = NULL, keys_endpoint = 'u9c_keys_complete',
  parent_dataset = NULL, key_path = NULL,
  page_size = 2000, max_pages = 5,
  notes = 'U9完工报告(GC-2026*)按键拉单，键源CommonEntity枚举', updated_at = now()
WHERE dataset_code = 'ds_u9c_complete';

UPDATE sync_configs SET
  keys_sql = NULL, keys_endpoint = NULL,
  parent_dataset = 'ds_u9c_complete', key_path = 'm_mO.m_code',
  page_size = 1, max_pages = 8000,
  notes = '按完工报告的工单号拉U9生产订单', updated_at = now()
WHERE dataset_code = 'ds_u9c_mo';

UPDATE sync_configs SET
  enabled = FALSE,
  keys_sql = NULL, parent_dataset = 'ds_u9c_mo', key_path = 'm_docNo',
  notes = '停用：原SQL接口已下架，合同/销售订单号已并入 ds_u9c_mo 的 contract_no/so_no', updated_at = now()
WHERE dataset_code = 'ds_u9c_so';

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_u9c_mo', 'm_project.m_code', 'contract_no', NULL, 95),
  ('string', 'ds_u9c_mo', 'm_project.m_code', 'so_no', NULL, 96)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

DELETE FROM relations
WHERE report_code = 'rpt_hang_complete' AND left_dataset = 'ds_u9c_mo' AND right_dataset = 'ds_u9c_so';

UPDATE reports SET
  fields_json = REPLACE(REPLACE(fields_json,
      '"ds_u9c_so.contract_no"', '"ds_u9c_mo.contract_no"'),
      '"ds_u9c_so.so_no"', '"ds_u9c_mo.so_no"'),
  updated_at = now()
WHERE code = 'rpt_hang_complete';
