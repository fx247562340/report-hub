-- 本地模式筛选键 → 投影列映射（上游参数名 endImmersionTime → hang_end_time）
UPDATE reports SET filters_json = $ff$
[
  {"key":"endImmersionTime","label":"完工日期","op":"date_range","localField":"hang_end_time","options":[]},
  {"key":"work_status","label":"挂次状态","op":"select","options":[{"value":"12","label":"白件已称重"},{"value":"1","label":"未酸洗"},{"value":"2","label":"已酸洗"},{"value":"3","label":"已称重"},{"value":"4","label":"已浸锌"},{"value":"5","label":"已完成"}]}
]
$ff$, updated_at = now() WHERE code = 'rpt_hang_complete';
