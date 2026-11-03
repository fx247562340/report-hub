-- 表级全量同步：SQL 枚举上游表全部键 + 文档接口拉原文（与父表无关）
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS keys_sql TEXT;
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS keys_endpoint VARCHAR(64);

UPDATE sync_configs SET mode='keys', keys_endpoint='u9c_mo_so_contract',
  keys_sql='SELECT m.DocNo AS DocNo FROM MO_MO m', parent_dataset=NULL, key_path=NULL,
  notes='表级全量：MO_MO', updated_at=now() WHERE dataset_code='ds_u9c_mo';
UPDATE sync_configs SET mode='keys', keys_endpoint='u9c_mo_so_contract',
  keys_sql='SELECT r.DocNo AS DocNo FROM MO_CompleteRpt r', parent_dataset=NULL, key_path=NULL,
  notes='表级全量：完工报告', updated_at=now() WHERE dataset_code='ds_u9c_complete';
UPDATE sync_configs SET mode='keys', keys_endpoint='u9c_mo_so_contract',
  keys_sql='SELECT m.DocNo AS DocNo FROM MO_MO m', parent_dataset=NULL, key_path=NULL,
  notes='表级全量：按工单查销售订单/合同', updated_at=now() WHERE dataset_code='ds_u9c_so';
