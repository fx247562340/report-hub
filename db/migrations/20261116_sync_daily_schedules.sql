-- 夜间自动增量同步排程：挂次/完工/订单/黑件 02:00，白件错峰 02:10（按依赖顺序由调度器保证先后）
UPDATE sync_configs SET schedule_type = 'daily', schedule_time = '02:00', updated_at = now()
WHERE dataset_code IN ('ds_hang', 'ds_u9c_complete', 'ds_u9c_mo', 'ds_u9c_misc_rcv_l');
UPDATE sync_configs SET schedule_type = 'daily', schedule_time = '02:10', updated_at = now()
WHERE dataset_code = 'ds_u9c_ship_l';
