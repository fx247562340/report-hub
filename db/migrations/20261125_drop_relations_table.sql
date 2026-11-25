-- 2026-11-25: 报表关联规则已统一迁移至后端代码 ReportRelationRegistry，清理废弃的 relations 表
DROP TABLE IF EXISTS relations;
