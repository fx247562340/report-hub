-- 上游原始数据同步：通用 JSONB 落库 + 同步任务
-- 同步只搬运上游原始结构；报表映射/关联逻辑不变（查询时再映射）
-- 应用：docker exec -i report-hub-pg psql -U reporthub -d reporthub < db/migrations/20261103_sync_raw_store.sql

-- 同步配置（按数据集，不建业务表）
CREATE TABLE IF NOT EXISTS sync_configs (
  dataset_code   VARCHAR(64) PRIMARY KEY,
  enabled        BOOLEAN NOT NULL DEFAULT TRUE,
  mode           VARCHAR(16) NOT NULL DEFAULT 'list',  -- list：分页拉列表
  page_size      INT NOT NULL DEFAULT 200,
  max_pages      INT NOT NULL DEFAULT 500,
  notes          TEXT NOT NULL DEFAULT '',
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 同步任务
CREATE TABLE IF NOT EXISTS sync_jobs (
  id             BIGSERIAL PRIMARY KEY,
  dataset_code   VARCHAR(64) NOT NULL,
  status         VARCHAR(16) NOT NULL,          -- running | success | failed
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at    TIMESTAMPTZ,
  row_count      INT NOT NULL DEFAULT 0,
  page_count     INT NOT NULL DEFAULT 0,
  duration_ms    INT,
  error          TEXT,
  trigger        VARCHAR(16) NOT NULL DEFAULT 'manual'  -- manual | schedule
);
CREATE INDEX IF NOT EXISTS ix_sync_jobs_ds ON sync_jobs (dataset_code, started_at DESC);

-- 原始数据（上游整行 JSON），一张表覆盖所有上游结构
CREATE TABLE IF NOT EXISTS sync_records (
  id             BIGSERIAL PRIMARY KEY,
  dataset_code   VARCHAR(64) NOT NULL,
  source_key     TEXT,                            -- 业务键（可选，用于去重）
  payload        JSONB NOT NULL,
  batch_id       BIGINT,
  synced_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_sync_records_ds ON sync_records (dataset_code, synced_at DESC);
CREATE INDEX IF NOT EXISTS ix_sync_records_key ON sync_records (dataset_code, source_key);
CREATE INDEX IF NOT EXISTS ix_sync_records_payload ON sync_records USING GIN (payload);

-- 全局应用设置（查询模式等）
CREATE TABLE IF NOT EXISTS app_settings (
  key            VARCHAR(64) PRIMARY KEY,
  value          TEXT NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO app_settings (key, value, updated_at) VALUES ('query_mode', 'local', now())
ON CONFLICT (key) DO NOTHING;

-- 默认给已有列表型数据集开同步配置
INSERT INTO sync_configs (dataset_code, enabled, mode, page_size, max_pages, notes, schedule_type, updated_at)
SELECT code, TRUE, 'list', 200, 500, 'auto from datasets', 'manual', now() FROM datasets
WHERE endpoint_code IN ('completed_hangup_list','crm_so_list')
ON CONFLICT (dataset_code) DO NOTHING;
