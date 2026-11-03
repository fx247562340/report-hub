-- 同步任务调度：频率 / 时间点
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(16) NOT NULL DEFAULT 'manual';
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS schedule_time VARCHAR(8);
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS schedule_weekday INT;
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS schedule_interval_min INT;
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS last_schedule_run TIMESTAMPTZ;
ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS next_schedule_run TIMESTAMPTZ;
