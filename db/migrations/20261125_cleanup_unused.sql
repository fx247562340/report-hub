-- 2026-11-25 清理已废弃的历史对象与旧看板表
-- 1) dashboard_widgets 已被新版经营驾驶舱（/api/dashboard/overview）完全替代
DROP TABLE IF EXISTS dashboard_widgets;

-- 2) ds_u9c_so / u9c_mo_so_contract 对应的 U9 QueryInfoBySql 接口已下架，已由 ds_u9c_soline_galv 替代
DELETE FROM sync_jobs WHERE dataset_code = 'ds_u9c_so';
DELETE FROM sync_records WHERE dataset_code = 'ds_u9c_so';
DELETE FROM sync_configs WHERE dataset_code = 'ds_u9c_so';
DELETE FROM field_mappings WHERE dataset_code = 'ds_u9c_so';
DELETE FROM datasets WHERE code = 'ds_u9c_so';
DELETE FROM api_endpoints WHERE code = 'u9c_mo_so_contract';
