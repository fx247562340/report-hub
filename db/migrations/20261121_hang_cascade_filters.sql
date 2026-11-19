-- 挂次日报表筛选增加 客户/物料 级联下拉：
--   customer_name（客户）→ product_name（物料），1:N 联动
--   选客户后物料下拉只出该客户的物料；未选客户出全部物料；清空客户恢复全部
-- dynamic 标记走 /api/reports/{code}/options 动态选项；cascade 指定父筛选 key
-- 内存筛选按精确匹配：客户列可能一挂多客户（逗号分隔），物料筛选时若该行客户列表包含所选客户即视为候选行，
-- 但 product_name 行级精确匹配即可（物料本身属于该挂次行）

UPDATE reports SET
  filters_json = $ff$
[
  {
    "key": "endImmersionTime",
    "label": "完工日期",
    "op": "date_range",
    "localField": "hang_end_time",
    "options": []
  },
  {
    "key": "work_status",
    "label": "挂次状态",
    "op": "select",
    "options": [
      {"value": "12", "label": "白件已称重"},
      {"value": "1", "label": "未酸洗"},
      {"value": "2", "label": "已酸洗"},
      {"value": "3", "label": "已称重"},
      {"value": "4", "label": "已浸锌"},
      {"value": "5", "label": "已完成"}
    ]
  },
  {
    "key": "customer_name",
    "label": "客户",
    "op": "select",
    "dynamic": true,
    "options": []
  },
  {
    "key": "product_name",
    "label": "物料",
    "op": "select",
    "dynamic": true,
    "cascade": "customer_name",
    "options": []
  }
]
$ff$,
  updated_at = now()
WHERE code = 'rpt_hang_complete';
