-- P0 落库瘦身：移除未使用的 sync_records(payload) GIN 索引，消除 22 倍表膨胀
DROP INDEX IF EXISTS ix_sync_records_payload;
