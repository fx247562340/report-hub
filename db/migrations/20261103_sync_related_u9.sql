-- U9/ERP 关联同步：按父数据集键逐个拉子接口，存原始行
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS parent_dataset VARCHAR(64);
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS key_path VARCHAR(255);

INSERT INTO sync_configs (dataset_code, enabled, mode, page_size, max_pages, parent_dataset, key_path, notes, schedule_type, updated_at) VALUES
  ('ds_u9c_complete', TRUE, 'related', 1, 5000, 'ds_hang', 'hangNo', '按挂次号查 U9 完工报告', 'manual', now()),
  ('ds_u9c_mo',        TRUE, 'related', 1, 5000, 'ds_u9c_complete', 'm_mO.m_code', '按工单号查 U9 生产订单', 'manual', now()),
  ('ds_u9c_so',        TRUE, 'related', 1, 5000, 'ds_u9c_mo', 'm_docNo', '按工单号查销售订单/合同', 'manual', now())
ON CONFLICT (dataset_code) DO UPDATE SET
  mode = EXCLUDED.mode, parent_dataset = EXCLUDED.parent_dataset,
  key_path = EXCLUDED.key_path, notes = EXCLUDED.notes, updated_at = now();
