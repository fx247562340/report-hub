-- 挂次日报表：新增「平均吨上锌量」= 锌耗 / (镀锌后白件重量/1000)
-- 应用：docker exec -i report-hub-pg psql -U reporthub -d reporthub < db/migrations/20261005_hang_report_zinc_per_ton.sql
UPDATE reports
SET fields_json = $fn$
[
  {
    "key": "prod_status",
    "label": "生产状态",
    "from": "ds_hang.prod_status"
  },
  {
    "key": "customer_name",
    "label": "客户",
    "from": "ds_hang.customer_name"
  },
  {
    "key": "contract_no",
    "label": "合同编号",
    "from": "ds_u9c_so.contract_no"
  },
  {
    "key": "product_name",
    "label": "产品名称",
    "from": "ds_hang.product_name"
  },
  {
    "key": "item_code",
    "label": "物料编码",
    "from": "ds_hang.item_code"
  },
  {
    "key": "so_no",
    "label": "销售订单号",
    "from": "ds_u9c_so.so_no"
  },
  {
    "key": "line_name",
    "label": "产线",
    "from": "ds_hang.line_name"
  },
  {
    "key": "shift_name",
    "label": "班别",
    "from": "ds_hang.shift_name"
  },
  {
    "key": "work_date",
    "label": "排班日期",
    "from": "ds_hang.work_date"
  },
  {
    "key": "hang_no",
    "label": "挂次编号",
    "from": "ds_hang.hang_no"
  },
  {
    "key": "hanger_name",
    "label": "挂具名称",
    "from": "ds_hang.hanger_name"
  },
  {
    "key": "order_weight",
    "label": "订单重量",
    "from": "ds_hang.order_weight"
  },
  {
    "key": "hang_start_time",
    "label": "挂次开始时间",
    "from": "ds_hang.hang_start_time"
  },
  {
    "key": "hang_end_time",
    "label": "挂次结束时间",
    "from": "ds_hang.hang_end_time"
  },
  {
    "key": "est_weight",
    "label": "暂估重量",
    "from": "ds_hang.est_weight"
  },
  {
    "key": "corrosion_level",
    "label": "锈蚀程度",
    "from": "ds_hang.corrosion_level"
  },
  {
    "key": "is_normal",
    "label": "是否正常",
    "from": "ds_hang.is_normal"
  },
  {
    "key": "exec_standard",
    "label": "执行标准",
    "from": "ds_hang.exec_standard"
  },
  {
    "key": "acid_black_weight",
    "label": "酸洗前黑件重量（kg）",
    "from": "ds_hang.acid_black_weight"
  },
  {
    "key": "black_weight",
    "label": "镀锌前黑件重量",
    "from": "ds_hang.black_weight"
  },
  {
    "key": "white_weight",
    "label": "镀锌后白件重量",
    "from": "ds_hang.white_weight"
  },
  {
    "key": "iron_loss",
    "label": "铁损",
    "from": "iron_loss"
  },
  {
    "key": "iron_abnormal",
    "label": "铁损异常",
    "from": "iron_abnormal"
  },
  {
    "key": "zinc_loss",
    "label": "锌耗",
    "from": "zinc_loss"
  },
  {
    "key": "zinc_abnormal",
    "label": "锌耗异常",
    "from": "zinc_abnormal"
  },
  {
    "key": "zinc_per_ton",
    "label": "平均吨上锌量",
    "from": "zinc_per_ton"
  },
  {
    "key": "pickling_start",
    "label": "酸洗开始时间",
    "from": "ds_hang.pickling_start"
  },
  {
    "key": "pickling_secs",
    "label": "酸洗时长（s）",
    "from": "ds_hang.pickling_secs"
  },
  {
    "key": "weigh_time",
    "label": "称重时间",
    "from": "ds_hang.weigh_time"
  },
  {
    "key": "zinc_start_time",
    "label": "浸锌开始时间",
    "from": "ds_hang.zinc_start_time"
  },
  {
    "key": "zinc_end_time",
    "label": "浸锌结束时间",
    "from": "ds_hang.zinc_end_time"
  },
  {
    "key": "zinc_start_temp",
    "label": "镀锌开始温度",
    "from": "ds_hang.zinc_start_temp"
  },
  {
    "key": "zinc_end_temp",
    "label": "镀锌结束温度",
    "from": "ds_hang.zinc_end_temp"
  },
  {
    "key": "zinc_secs",
    "label": "浸锌时间（s）",
    "from": "ds_hang.zinc_secs"
  },
  {
    "key": "remark",
    "label": "备注",
    "from": "ds_hang.remark"
  },
  {
    "key": "pickling_tank",
    "label": "酸洗池号",
    "from": "ds_hang.pickling_tank"
  },
  {
    "key": "weight_method",
    "label": "重量记录方式",
    "from": "ds_hang.weight_method"
  }
]
$fn$,
    calcs_json = $cn$
[
  {
    "key": "zinc_loss",
    "label": "锌耗",
    "kind": "formula",
    "formula": "white_weight - black_weight",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "zinc_abnormal",
    "label": "锌耗异常",
    "kind": "condition",
    "formula": "",
    "when": "black_weight > white_weight",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "zinc_per_ton",
    "label": "平均吨上锌量",
    "kind": "formula",
    "formula": "zinc_loss / (white_weight / 1000)",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "iron_loss",
    "label": "铁损",
    "kind": "formula",
    "formula": "acid_black_weight - black_weight",
    "when": "",
    "then": "是",
    "else": "否",
    "precision": 3
  },
  {
    "key": "iron_abnormal",
    "label": "铁损异常",
    "kind": "condition",
    "formula": "",
    "when": "(acid_black_weight - black_weight) < 0",
    "then": "是",
    "else": "否",
    "precision": 3
  }
]
$cn$,
    updated_at = now()
WHERE code = 'rpt_hang_complete';
