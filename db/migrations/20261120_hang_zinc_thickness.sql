-- 挂次日报表增加锌层厚度两列：测量值(measureArr 12点) / 平均值(measureAvg)
-- 数据源：MES PDM_G050/list，按 hangNo 过滤（接口已验证：hangNo 精确匹配，返回 measureArr/measureAvg）
-- 关联模式 child_lookup：挂次日报主表按挂次号逐条调 G050 并发拉取

INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, query_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'mes_zinc_thickness', 'MES锌层厚度(按挂次)', 'ruima_mes', 'GET', '/PDM_G050/list',
  '{"page": 1, "limit": 5, "lineId": 15, "shiftId": "", "workDate": "", "customerName": "", "productName": "", "hangNo": "{{key}}"}',
  '{}', 'data', NULL, 'none', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, query_template = EXCLUDED.query_template,
  headers_template = EXCLUDED.headers_template, list_path = EXCLUDED.list_path,
  total_path = EXCLUDED.total_path, pagination = EXCLUDED.pagination, updated_at = now();

INSERT INTO datasets (code, name, endpoint_code, fetch_hint, updated_at)
VALUES ('ds_mes_zinc_thickness', 'MES锌层厚度', 'mes_zinc_thickness', 'list', now())
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, endpoint_code = EXCLUDED.endpoint_code,
  fetch_hint = EXCLUDED.fetch_hint, updated_at = now();

INSERT INTO field_mappings (data_type, dataset_code, source_path, target_field, transform, sort)
VALUES
  ('string', 'ds_mes_zinc_thickness', 'hangNo', 'hang_no_zinc', NULL, 10),
  ('string', 'ds_mes_zinc_thickness', 'measureArr', 'zinc_thickness_values', NULL, 20),
  ('number', 'ds_mes_zinc_thickness', 'measureAvg', 'zinc_thickness_avg', NULL, 30),
  ('number', 'ds_mes_zinc_thickness', 'measurePoint', 'zinc_thickness_points', NULL, 40)
ON CONFLICT (dataset_code, target_field) DO UPDATE SET
  source_path = EXCLUDED.source_path, data_type = EXCLUDED.data_type,
  transform = EXCLUDED.transform, sort = EXCLUDED.sort;

-- 关联关系：挂次日报主表(rpt_hang_complete) ds_hang × ds_mes_zinc_thickness，按挂次号
INSERT INTO relations (code, report_code, left_dataset, right_dataset, join_type, cardinality,
                       on_fields, fetch_mode, fetch_config, expand_mode, sort, updated_at)
VALUES (
  'rel_hang_zinc', 'rpt_hang_complete', 'ds_hang', 'ds_mes_zinc_thickness',
  'left', '1-1', '[{"leftField":"hang_no","rightField":"hang_no_zinc"}]',
  'child_lookup',
  '{"keyField":"hang_no","concurrency":8}',
  'flat', 10, now()
)
ON CONFLICT (code) DO UPDATE SET
  report_code = EXCLUDED.report_code, left_dataset = EXCLUDED.left_dataset,
  right_dataset = EXCLUDED.right_dataset, join_type = EXCLUDED.join_type,
  cardinality = EXCLUDED.cardinality, on_fields = EXCLUDED.on_fields,
  fetch_mode = EXCLUDED.fetch_mode, fetch_config = EXCLUDED.fetch_config,
  expand_mode = EXCLUDED.expand_mode, sort = EXCLUDED.sort, updated_at = now();

-- 报表列：在备注前插入两列锌层厚度
UPDATE reports SET
  fields_json = $fn$
[
  {"key": "prod_status", "label": "生产状态", "from": "ds_hang.prod_status"},
  {"key": "customer_name", "label": "客户", "from": "ds_hang.customer_name"},
  {"key": "contract_no", "label": "合同编号", "from": "ds_u9c_mo.contract_no"},
  {"key": "product_name", "label": "产品名称", "from": "ds_hang.product_name"},
  {"key": "item_code", "label": "物料编码", "from": "ds_hang.item_code"},
  {"key": "so_no", "label": "销售订单号", "from": "ds_u9c_mo.so_no"},
  {"key": "line_name", "label": "产线", "from": "ds_hang.line_name"},
  {"key": "shift_name", "label": "班别", "from": "ds_hang.shift_name"},
  {"key": "work_date", "label": "排班日期", "from": "ds_hang.work_date"},
  {"key": "hang_no", "label": "挂次编号", "from": "ds_hang.hang_no"},
  {"key": "hanger_name", "label": "挂具名称", "from": "ds_hang.hanger_name"},
  {"key": "order_weight", "label": "订单重量", "from": "ds_hang.order_weight"},
  {"key": "hang_start_time", "label": "挂次开始时间", "from": "ds_hang.hang_start_time"},
  {"key": "hang_end_time", "label": "挂次结束时间", "from": "ds_hang.hang_end_time"},
  {"key": "est_weight", "label": "暂估重量", "from": "ds_hang.est_weight"},
  {"key": "corrosion_level", "label": "锈蚀程度", "from": "ds_hang.corrosion_level"},
  {"key": "is_normal", "label": "是否正常", "from": "ds_hang.is_normal"},
  {"key": "exec_standard", "label": "执行标准", "from": "ds_hang.exec_standard"},
  {"key": "acid_black_weight", "label": "酸洗前黑件重量（kg）", "from": "ds_hang.acid_black_weight"},
  {"key": "black_weight", "label": "镀锌前黑件重量", "from": "ds_hang.black_weight"},
  {"key": "white_weight", "label": "镀锌后白件重量", "from": "ds_hang.white_weight"},
  {"key": "iron_loss", "label": "铁损", "from": "iron_loss"},
  {"key": "iron_abnormal", "label": "铁损异常", "from": "iron_abnormal"},
  {"key": "zinc_loss", "label": "锌耗", "from": "zinc_loss"},
  {"key": "zinc_abnormal", "label": "锌耗异常", "from": "zinc_abnormal"},
  {"key": "zinc_per_ton", "label": "平均吨上锌量", "from": "zinc_per_ton"},
  {"key": "zinc_thickness_values", "label": "锌层厚度测量值", "from": "ds_mes_zinc_thickness.zinc_thickness_values"},
  {"key": "zinc_thickness_avg", "label": "锌层厚度平均值", "from": "ds_mes_zinc_thickness.zinc_thickness_avg"},
  {"key": "pickling_start", "label": "酸洗开始时间", "from": "ds_hang.pickling_start"},
  {"key": "pickling_secs", "label": "酸洗时长（s）", "from": "ds_hang.pickling_secs"},
  {"key": "weigh_time", "label": "称重时间", "from": "ds_hang.weigh_time"},
  {"key": "zinc_start_time", "label": "浸锌开始时间", "from": "ds_hang.zinc_start_time"},
  {"key": "zinc_end_time", "label": "浸锌结束时间", "from": "ds_hang.zinc_end_time"},
  {"key": "zinc_start_temp", "label": "镀锌开始温度", "from": "ds_hang.zinc_start_temp"},
  {"key": "zinc_end_temp", "label": "镀锌结束温度", "from": "ds_hang.zinc_end_temp"},
  {"key": "zinc_secs", "label": "浸锌时间（s）", "from": "ds_hang.zinc_secs"},
  {"key": "remark", "label": "备注", "from": "ds_hang.remark"},
  {"key": "pickling_tank", "label": "酸洗池号", "from": "ds_hang.pickling_tank"},
  {"key": "weight_method", "label": "重量记录方式", "from": "ds_hang.weight_method"}
]
$fn$,
  updated_at = now()
WHERE code = 'rpt_hang_complete';

-- 本地模式数据源：G050 全量同步（按 hangNo 覆盖历史挂次），夜间 02:20 增量刷新
INSERT INTO sync_configs (dataset_code, enabled, mode, page_size, max_pages, notes,
                          schedule_type, schedule_time, updated_at)
VALUES
  ('ds_mes_zinc_thickness', TRUE, 'list', 2000, 20, 'MES锌层厚度全量（PDM_G050，1号线）',
   'daily', '18:20', now())
ON CONFLICT (dataset_code) DO UPDATE SET
  enabled = EXCLUDED.enabled, mode = EXCLUDED.mode, page_size = EXCLUDED.page_size,
  max_pages = EXCLUDED.max_pages, notes = EXCLUDED.notes,
  schedule_type = EXCLUDED.schedule_type, schedule_time = EXCLUDED.schedule_time,
  updated_at = now();

-- 同步专用端点：大分页全量拉（child_lookup 用小 limit 的 mes_zinc_thickness 保持不变）
INSERT INTO api_endpoints (
  code, name, data_source_code, method, path, query_template,
  headers_template, list_path, total_path, pagination, updated_at
) VALUES (
  'mes_zinc_thickness_sync', 'MES锌层厚度全量同步', 'ruima_mes', 'GET', '/PDM_G050/list',
  '{"page": "{{page}}", "limit": "{{pageSize}}", "lineId": 15}',
  '{}', 'data', 'count', 'page', now()
)
ON CONFLICT (code) DO UPDATE SET
  name = EXCLUDED.name, data_source_code = EXCLUDED.data_source_code,
  method = EXCLUDED.method, path = EXCLUDED.path, query_template = EXCLUDED.query_template,
  headers_template = EXCLUDED.headers_template, list_path = EXCLUDED.list_path,
  total_path = EXCLUDED.total_path, pagination = EXCLUDED.pagination, updated_at = now();

UPDATE sync_configs SET notes = 'MES锌层厚度全量（PDM_G050，1号线，limit=2000 分页）', updated_at = now()
WHERE dataset_code = 'ds_mes_zinc_thickness';

-- 数据集改指向同步端点（大分页）；child_lookup 关联在 fetch_config 指定原端点
UPDATE datasets SET endpoint_code = 'mes_zinc_thickness_sync', updated_at = now()
WHERE code = 'ds_mes_zinc_thickness';

UPDATE relations SET fetch_config = '{"keyField":"hang_no","concurrency":8,"rightEndpoint":"mes_zinc_thickness"}', updated_at = now()
WHERE code = 'rel_hang_zinc';

-- 修正：child_lookup(fetchByKeys) 读取的是 endpoint/detailEndpoint 键，不是 rightEndpoint
UPDATE relations SET fetch_config = '{"keyField":"hang_no","concurrency":8,"endpoint":"mes_zinc_thickness"}', updated_at = now()
WHERE code = 'rel_hang_zinc';
