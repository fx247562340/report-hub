-- 同步策略：默认增量（只取上次同步后的新数据，按行 upsert），全量保留手动触发（清库重写）。
-- incremental_filter 描述「如何只取新数据」，由同步在请求上动态合并：
--   queryMerge    拼进查询串（MES 类）
--   filtersAppend 追加进 body 的 Filters 数组（CommonEntity 类）
--   值可用 {{sinceDate}}(yyyy-mm-dd) / {{sinceCompact}}(yyyymmdd) / {{sinceYM}}(yyyymm)
--   起点 = 上次成功同步时间回看 2 天；首次同步（无历史）自动走全量
-- 单号过滤（DocNo ge）比 BusinessDate 可靠：U9 对 BusinessDate 的过滤基本不生效
-- ds_u9c_mo 无过滤配置：键来自父数据集（完工报告）里新同步的行，天然增量

ALTER TABLE sync_configs ADD COLUMN IF NOT EXISTS incremental_filter TEXT;

UPDATE sync_configs SET incremental_filter = '{"queryMerge": {"workDateStart": "{{sinceDate}}"}}',
  updated_at = now() WHERE dataset_code = 'ds_hang';
UPDATE sync_configs SET incremental_filter = '{"filtersAppendMonths": {"Field": "MiscRcvTrans.DocNo", "Prefix": "Mis", "SinceYM": "{{sinceYM}}"}}',
  updated_at = now() WHERE dataset_code = 'ds_u9c_misc_rcv_l';
UPDATE sync_configs SET incremental_filter = '{"filtersAppendMonths": {"Field": "Ship.DocNo", "Prefix": "SM", "SinceYM": "{{sinceYM}}"}}',
  updated_at = now() WHERE dataset_code = 'ds_u9c_ship_l';
UPDATE sync_configs SET incremental_filter = '{"filtersAppendMonths": {"Field": "DocNo", "Prefix": "GC-", "SinceYM": "{{sinceYM}}"}}',
  updated_at = now() WHERE dataset_code = 'ds_u9c_complete';
UPDATE sync_configs SET incremental_filter = NULL, updated_at = now() WHERE dataset_code = 'ds_u9c_mo';

-- 行级数据集补业务键（单号#行号），供增量 upsert 识别同一行
UPDATE sync_records SET source_key = (payload->'MiscRcvTrans'->>'DocNo') || '#' || (payload->>'DocLineNo')
WHERE dataset_code = 'ds_u9c_misc_rcv_l' AND source_key IS NULL;
UPDATE sync_records SET source_key = (payload->'Ship'->>'DocNo') || '#' || (payload->>'DocLineNo')
WHERE dataset_code = 'ds_u9c_ship_l' AND source_key IS NULL;
