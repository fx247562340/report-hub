import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Rel = {
  code: string
  reportCode: string
  leftDataset: string
  rightDataset: string
  cardinality: string
  joinType: string
  onFields: string
  fetchMode: string
  fetchConfig: string
  expandMode: string
  sort?: number
}

type JoinPair = { leftField: string; rightField: string }

type GraphNodeKind = 'source' | 'dataset' | 'stage' | 'report'

type GraphNode = {
  id: string
  kind: GraphNodeKind
  col: number
  title: string
  subtitle: string
  tag?: string
  sourceName?: string
  badge?: string
  badgeTone?: 'ok' | 'muted' | 'warn' | 'err'
  meta?: string
  compact?: boolean
  x: number
  y: number
  w: number
  h: number
  refData?: Record<string, any>
}

type GraphEdge = {
  id: string
  from: string
  to: string
  label?: string
  subLabel?: string
  kind: 'flow' | 'join' | 'builtin'
  rel?: Rel
  detail?: string
  showPill?: boolean
}

const CARDINALITY = [
  { value: '1-1', label: '一对一' },
  { value: '1-N', label: '一对多（左一行 → 右多行）' },
  { value: 'N-1', label: '多对一（左多行 → 右一行）' },
  { value: 'N-N', label: '多对多' },
]
const JOIN_TYPE = [
  { value: 'left', label: '左连接' },
  { value: 'inner', label: '内连接' },
  { value: 'union', label: '并集' },
]
const FETCH_MODE = [
  { value: 'dual_list', label: '双侧列表再关联', hint: '两边各调 list，内存 hash join' },
  { value: 'child_batch', label: '批量 IN 查询', hint: '收集主表键，子接口 ?ids= 批量拉' },
  { value: 'child_lookup', label: '逐条单查', hint: '收集主表键，子接口 /{id} 并发拉' },
]
const EXPAND_MODE = [
  { value: 'flat', label: '摊平成多行', hint: 'Excel 友好，主字段重复' },
  { value: 'nested', label: '嵌套子列表', hint: '主表一行，子数据放数组' },
]

/** 《销售合同滚动统计报表》内置核算引擎的数据集与血缘定义（与 ContractScrollService 一致） */
const SCROLL_BUILTIN_SPECS: {
  dsCode: string
  role: string
  stageId: string
  edgeLabel: string
  detail: string
}[] = [
  { dsCode: 'ds_u9c_soline_galv', role: '新单发现', stageId: 'stage_contract', edgeLabel: '1104 新单发现', detail: '按 SO.DocNo / Project.Code 自动发现 2026-11 起新签镀锌销售订单行及单价、预计吨位' },
  { dsCode: 'ds_u9c_misc_rcv_l', role: '黑件来料', stageId: 'stage_stock', edgeLabel: '客户+料品分摊', detail: '客供原材料库杂收单行（MiscRcv003），按客户与料品归集/分摊至对应合同行 T 列' },
  { dsCode: 'ds_u9c_issue_bf003', role: '黑件出库', stageId: 'stage_stock', edgeLabel: '项目号匹配', detail: '客供原材料库生产领料行，按 Project.Code 或客户+料品匹配至合同行 U 列' },
  { dsCode: 'ds_u9c_complete_bf004', role: '白件入库', stageId: 'stage_stock', edgeLabel: '项目号匹配', detail: '镀锌成品库完工报告入库数量，归集至合同行 W 列' },
  { dsCode: 'ds_u9c_ship_l', role: '白件发货', stageId: 'stage_stock', edgeLabel: '项目号匹配', detail: '镀锌成品库出货单行发货吨位，归集至合同行 X 列并推导累计发货金额' },
  { dsCode: 'ds_u9c_arbill_galv', role: '开票应收', stageId: 'stage_finance', edgeLabel: '发票/应收归集', detail: '镀锌应收单行（已录发票号计入 Z 列开票金额，全部应收计入未做应收核对）' },
  { dsCode: 'ds_u9c_arbill_misc', role: '期初应收', stageId: 'stage_finance', edgeLabel: '期初挂账', detail: '手工录入期初应收单（SrcType=0），参与应收核销与欠款核对' },
  { dsCode: 'ds_u9c_recbill_use', role: '收款用途', stageId: 'stage_finance', edgeLabel: '核销追溯', detail: '收款单用途行（RecBillUseLine）结合核销链归集至合同行 AA 列回款金额' },
  { dsCode: 'ds_u9c_arapply_l', role: '应收核销', stageId: 'stage_finance', edgeLabel: '核销链分配', detail: '连接收款用途行与应收单号，实现精准按合同分配回款（未核销预收按 FIFO 归属）' },
]

function labelOf(list: { value: string; label: string }[], v: string) {
  return list.find((x) => x.value === v)?.label || v
}

function parsePairs(json: string): JoinPair[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) && arr.length
      ? arr.map((x) => ({ leftField: x.leftField || '', rightField: x.rightField || '' }))
      : [{ leftField: '', rightField: '' }]
  } catch {
    return [{ leftField: '', rightField: '' }]
  }
}

function parseCfg(json: string): Record<string, any> {
  try {
    const o = JSON.parse(json || '{}')
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}

function countJson(json?: string) {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

type LayoutResult = {
  nodes: GraphNode[]
  edges: GraphEdge[]
  colTitles: { col: number; x: number; label: string }[]
  colBounds: Map<number, { top: number; bottom: number }>
  width: number
  height: number
  sourceCount: number
}

function buildLineageGraph(
  report: any,
  relations: Rel[],
  datasources: any[],
  endpoints: any[],
  datasets: any[],
  syncStatuses: any[],
): LayoutResult {
  if (!report) {
    return { nodes: [], edges: [], colTitles: [], colBounds: new Map(), width: 860, height: 320, sourceCount: 0 }
  }

  const dsByCode = new Map(datasets.map((d) => [d.code, d]))
  const epByCode = new Map(endpoints.map((e) => [e.code, e]))
  const srcByCode = new Map(datasources.map((s) => [s.code, s]))
  const syncByCode = new Map(syncStatuses.map((s) => [s.datasetCode, s]))

  const nodes: GraphNode[] = []
  const edges: GraphEdge[] = []
  const addedNodes = new Set<string>()
  const addedEdges = new Set<string>()
  const touchedSources = new Set<string>()

  const pushNode = (n: Omit<GraphNode, 'x' | 'y' | 'w' | 'h'> & { w?: number; h?: number }) => {
    if (addedNodes.has(n.id)) return
    addedNodes.add(n.id)
    nodes.push({
      ...n,
      x: 0,
      y: 0,
      w: n.w || 208,
      h: n.h || (n.compact ? 54 : 84),
    })
  }
  const pushEdge = (e: GraphEdge) => {
    if (addedEdges.has(e.id)) return
    addedEdges.add(e.id)
    edges.push(e)
  }

  const makeDatasetNode = (
    dsCode: string,
    col: number,
    opts: {
      tag?: string
      compact?: boolean
      w?: number
      linkSourceNode?: boolean
      showSourcePill?: boolean
    } = {},
  ) => {
    const ds = dsByCode.get(dsCode)
    const ep = ds ? epByCode.get(ds.endpointCode) : null
    const src = ep ? srcByCode.get(ep.dataSourceCode) : null
    const sync = syncByCode.get(dsCode)
    if (src) touchedSources.add(src.code)
    const rowCnt = sync?.recordCount
    const badge = rowCnt != null ? `${Number(rowCnt).toLocaleString('zh-CN')} 行` : undefined
    const badgeTone: GraphNode['badgeTone'] =
      sync?.lastJobStatus === 'failed' ? 'err' : rowCnt > 0 ? 'ok' : 'muted'

    pushNode({
      id: `ds:${dsCode}`,
      kind: 'dataset',
      col,
      compact: opts.compact,
      w: opts.w || (opts.compact ? 212 : 206),
      h: opts.compact ? 54 : 86,
      title: ds?.name || dsCode,
      subtitle: opts.compact ? (ep?.code || dsCode) : dsCode,
      tag: opts.tag,
      sourceName: opts.showSourcePill ? src?.name : undefined,
      badge,
      badgeTone,
      meta: opts.compact ? undefined : (ep ? `${ep.method || 'GET'} ${ep.path}` : '未绑定接口'),
      refData: { ds, ep, src, sync },
    })

    if (opts.linkSourceNode && src) {
      const srcId = `src:${src.code}`
      pushNode({
        id: srcId,
        kind: 'source',
        col: 0,
        title: src.name || src.code,
        subtitle: src.baseUrl || src.code,
        tag: src.authType === 'u9c_oauth' ? 'U9C OAuth' : src.authType === 'session' ? '会话认证' : src.authType,
        w: 184,
        h: 78,
        refData: { src },
      })
      pushEdge({
        id: `e:${srcId}->ds:${dsCode}`,
        from: srcId,
        to: `ds:${dsCode}`,
        label: ep?.code,
        kind: 'flow',
        showPill: !opts.compact,
        detail: ep ? `接口 ${ep.name}（${ep.method || 'GET'} ${ep.path}）` : undefined,
      })
    }
  }

  const isScroll = report.code === 'rpt_contract_scroll'
  let colTitlesRaw: { col: number; label: string }[] = []
  const gapAfterCol = new Map<number, number>()

  if (isScroll) {
    // 销售合同滚动统计报表：4 列无跨列穿越的内置核算血缘图
    // col 0: PostgreSQL 基线 + 用友U9C 数据源
    // col 1: 10 张业务输入表（期初基线表 + 9 张 U9 同步表，紧凑卡片全览）
    // col 2: 3 个内置核算环节
    // col 3: 报表输出
    touchedSources.add('pg_baseline')
    pushNode({
      id: 'src:pg_baseline',
      kind: 'source',
      col: 0,
      title: '本地数据库',
      subtitle: 'PostgreSQL',
      tag: '内置存储',
      w: 176,
      h: 72,
      refData: {
        baseline: true,
        desc: '本地 PostgreSQL 数据库，保存期初基线表及全部增量同步的业务明细表。',
      },
    })

    // 期初基线表作为 col 1 首行输入表，彻底避免跨列连线穿越
    pushNode({
      id: 'tbl:baseline',
      kind: 'dataset',
      col: 1,
      compact: true,
      w: 212,
      h: 54,
      title: '期初基线表',
      subtitle: 'contract_scroll_baselines',
      tag: '2026-10 基线',
      badge: '期初起点',
      badgeTone: 'ok',
      refData: {
        baseline: true,
        desc: '保存 2026-10 期初黑白件库存、期初累计开票/回款及纸质合同号与 U9 项目号映射关系',
      },
    })
    pushEdge({
      id: 'e:src:pg_baseline->tbl:baseline',
      from: 'src:pg_baseline',
      to: 'tbl:baseline',
      kind: 'flow',
      showPill: false,
      detail: '从本地 PostgreSQL 读取 2026-10 期初基线行',
    })

    for (const sp of SCROLL_BUILTIN_SPECS) {
      makeDatasetNode(sp.dsCode, 1, {
        tag: sp.role,
        compact: true,
        w: 212,
        linkSourceNode: true,
      })
    }

    pushNode({
      id: 'stage_contract',
      kind: 'stage',
      col: 2,
      title: '合同主档与新单合并',
      subtitle: '期初基线 + U9 1104 新单',
      tag: '核算环节 1',
      meta: '自动合并一合同多料品行',
      w: 204,
      h: 82,
      refData: { desc: '以 2026-10 基线行作为起点，自动纳入 2026-11 及之后 U9 新签镀锌销售订单行。' },
    })
    pushNode({
      id: 'stage_stock',
      kind: 'stage',
      col: 2,
      title: '黑白件月度滚动结存',
      subtitle: 'BF003 黑件 · BF004 白件',
      tag: '核算环节 2',
      meta: '期末 = 期初 + 累计入 - 累计出',
      w: 204,
      h: 82,
      refData: { desc: '按项目号、客户+料品后缀逐月归集四类出入库单据，滚动推导黑白件线上暂存与期末库存。' },
    })
    pushNode({
      id: 'stage_finance',
      kind: 'stage',
      col: 2,
      title: '开票回款核销与欠款',
      subtitle: '应收单 + 收款 + 核销链',
      tag: '核算环节 3',
      meta: '已发未开 · 已开未回 · 总欠款',
      w: 204,
      h: 82,
      refData: { desc: '经 ARApplyLine 将收款单精准核销至对应应收与合同，未核销预收按客户欠款 FIFO 分配。' },
    })

    pushEdge({
      id: 'e:tbl:baseline->stage_contract',
      from: 'tbl:baseline',
      to: 'stage_contract',
      label: '期初基线行',
      kind: 'builtin',
      showPill: true,
      detail: '导入 2026-10 期初结存与合同号映射作为月度滚动起点',
    })

    for (const sp of SCROLL_BUILTIN_SPECS) {
      pushEdge({
        id: `e:ds:${sp.dsCode}->${sp.stageId}`,
        from: `ds:${sp.dsCode}`,
        to: sp.stageId,
        label: sp.edgeLabel,
        kind: 'builtin',
        showPill: true,
        detail: sp.detail,
      })
    }

    const rptId = `rpt:${report.code}`
    pushNode({
      id: rptId,
      kind: 'report',
      col: 3,
      title: report.name,
      subtitle: report.code,
      tag: '内置滚动引擎',
      badge: '42 列 · 双层表头',
      badgeTone: 'ok',
      meta: '按月滚动查询与分组导出',
      w: 204,
      h: 92,
      refData: { report },
    })

    for (const st of ['stage_contract', 'stage_stock', 'stage_finance']) {
      pushEdge({
        id: `e:${st}->${rptId}`,
        from: st,
        to: rptId,
        label: '汇总输出',
        kind: 'flow',
        showPill: false,
        detail: '汇总输出至《销售合同滚动统计报表》42 列结构',
      })
    }

    gapAfterCol.set(0, 62)
    gapAfterCol.set(1, 134)
    gapAfterCol.set(2, 68)

    colTitlesRaw = [
      { col: 0, label: '数据源' },
      { col: 1, label: '同步数据集 / 基线表（10）' },
      { col: 2, label: '内置核算链路' },
      { col: 3, label: '报表输出' },
    ]
  } else if (relations.length === 0) {
    // 单表报表：数据源 -> 主数据集 / 接口 -> 报表输出（3 列无跨列边）
    const rootCode = report.rootDataset
    if (rootCode) {
      makeDatasetNode(rootCode, 1, {
        tag: '主数据集',
        w: 224,
        linkSourceNode: true,
      })
    }

    const rptId = `rpt:${report.code}`
    const fieldCnt = countJson(report.fieldsJson)
    const filterCnt = countJson(report.filtersJson)
    pushNode({
      id: rptId,
      kind: 'report',
      col: 2,
      title: report.name,
      subtitle: report.code,
      tag: '单表聚合',
      badge: `${fieldCnt} 列 · ${filterCnt} 筛选`,
      badgeTone: 'ok',
      meta: '支持本地优先 / 实时切换',
      w: 214,
      h: 90,
      refData: { report },
    })

    if (rootCode) {
      pushEdge({
        id: `e:ds:${rootCode}->${rptId}`,
        from: `ds:${rootCode}`,
        to: rptId,
        label: '字段投影与汇总',
        kind: 'flow',
        showPill: true,
        detail: '直接基于主数据集执行字段映射、过滤与分页汇总',
      })
    }

    gapAfterCol.set(0, 146)
    gapAfterCol.set(1, 136)

    colTitlesRaw = [
      { col: 0, label: '数据源' },
      { col: 1, label: '同步数据集 / 接口' },
      { col: 2, label: '报表输出' },
    ]
  } else {
    // 多表关联报表：数据源徽标内嵌在数据集卡片顶部，消除跨列连线穿越
    // col 0: 主数据集 | col 1..N: 关联层 | last: 报表输出
    const rootCode = report.rootDataset
    const depth = new Map<string, number>()
    if (rootCode) depth.set(rootCode, 0)

    for (let pass = 0; pass < relations.length + 2; pass++) {
      for (const rel of relations) {
        const ld = depth.get(rel.leftDataset) ?? 0
        depth.set(rel.leftDataset, ld)
        const rd = depth.get(rel.rightDataset)
        if (rd == null || rd <= ld) {
          depth.set(rel.rightDataset, ld + 1)
        }
      }
    }

    let maxDatasetCol = 0
    for (const [dsCode, col] of depth.entries()) {
      if (col > maxDatasetCol) maxDatasetCol = col
      makeDatasetNode(dsCode, col, {
        tag: dsCode === rootCode ? '主数据集' : '关联表',
        w: 204,
        linkSourceNode: false,
        showSourcePill: true,
      })
    }

    for (const rel of relations) {
      const ps = parsePairs(rel.onFields).filter((p) => p.leftField && p.rightField)
      const onStr = ps.map((p) => `${p.leftField} = ${p.rightField}`).join(' & ')
      const joinTag = `${labelOf(JOIN_TYPE, rel.joinType)} · ${rel.cardinality}`
      pushEdge({
        id: `rel:${rel.code}`,
        from: `ds:${rel.leftDataset}`,
        to: `ds:${rel.rightDataset}`,
        label: onStr || rel.code,
        subLabel: joinTag,
        kind: 'join',
        rel,
        showPill: true,
        detail: `${labelOf(JOIN_TYPE, rel.joinType)}（${labelOf(CARDINALITY, rel.cardinality)}），实时取数策略：${labelOf(FETCH_MODE, rel.fetchMode)}`,
      })
    }

    const rptCol = maxDatasetCol + 1
    const rptId = `rpt:${report.code}`
    const fieldCnt = countJson(report.fieldsJson)
    const filterCnt = countJson(report.filtersJson)
    pushNode({
      id: rptId,
      kind: 'report',
      col: rptCol,
      title: report.name,
      subtitle: report.code,
      tag: '多表关联引擎',
      badge: `${fieldCnt} 列 · ${filterCnt} 筛选`,
      badgeTone: 'ok',
      meta: '本地内存 Join / 实时并发查',
      w: 204,
      h: 92,
      refData: { report },
    })

    const hasOutJoin = new Set(relations.map((r) => r.leftDataset))
    for (const dsCode of depth.keys()) {
      if (!hasOutJoin.has(dsCode)) {
        pushEdge({
          id: `e:ds:${dsCode}->${rptId}`,
          from: `ds:${dsCode}`,
          to: rptId,
          label: '拼表输出',
          kind: 'flow',
          showPill: false,
          detail: '关联结果参与公式计算后输出至最终报表列',
        })
      }
    }

    for (let c = 0; c < maxDatasetCol; c++) {
      gapAfterCol.set(c, 162)
    }
    gapAfterCol.set(maxDatasetCol, 76)

    colTitlesRaw = [{ col: 0, label: '主数据集 / 接口' }]
    for (let c = 1; c <= maxDatasetCol; c++) {
      colTitlesRaw.push({ col: c, label: maxDatasetCol === 1 ? '关联数据集' : `关联层 ${c}` })
    }
    colTitlesRaw.push({ col: rptCol, label: '报表输出' })
  }

  // 自动坐标布局：按列纵向居中，支持可变列间距与紧凑模式
  const byCol = new Map<number, GraphNode[]>()
  for (const n of nodes) {
    byCol.set(n.col, [...(byCol.get(n.col) || []), n])
  }
  const colsSorted = Array.from(byCol.keys()).sort((a, b) => a - b)
  const padX = 24
  const padTop = 52
  const padBottom = 34

  const colWidth = new Map<number, number>()
  const colHeight = new Map<number, number>()
  const colGapY = new Map<number, number>()
  let maxColH = 0
  for (const c of colsSorted) {
    const list = byCol.get(c) || []
    const gY = list.length >= 6 ? 8 : list.length >= 3 ? 28 : 22
    colGapY.set(c, gY)
    const w = Math.max(...list.map((n) => n.w), 176)
    const h = list.reduce((sum, n) => sum + n.h, 0) + Math.max(0, list.length - 1) * gY
    colWidth.set(c, w)
    colHeight.set(c, h)
    if (h > maxColH) maxColH = h
  }

  const colX = new Map<number, number>()
  const colBounds = new Map<number, { top: number; bottom: number }>()
  let curX = padX
  for (let idx = 0; idx < colsSorted.length; idx++) {
    const c = colsSorted[idx]
    colX.set(c, curX)
    const list = byCol.get(c) || []
    const gY = colGapY.get(c) || 18
    const ch = colHeight.get(c) || 0

    // 内置滚动报表 col 0 特殊对齐：PostgreSQL 基线对齐第 1 行，U9C 对齐下方 9 行中心
    if (isScroll && c === 0 && list.length === 2) {
      const topNode = list[0]
      const u9Node = list[1]
      topNode.x = curX
      topNode.y = padTop
      u9Node.x = curX
      u9Node.y = padTop + 54 + 8 + Math.round(((maxColH - 62) - u9Node.h) / 2)
      colBounds.set(c, { top: topNode.y, bottom: u9Node.y + u9Node.h })
    } else if (isScroll && c === 2 && list.length === 3) {
      // 三个核算环节分别与对应的输入行区间视觉对齐（行0-1、行2-5、行6-9）
      const anchors = [padTop + 18, padTop + 210, padTop + 458]
      list.forEach((n, i) => {
        n.x = curX
        n.y = anchors[i] ?? (padTop + i * 160)
      })
      colBounds.set(c, { top: list[0].y, bottom: list[list.length - 1].y + list[list.length - 1].h })
    } else {
      let curY = padTop + Math.max(0, Math.round((maxColH - ch) / 2))
      const startY = curY
      for (const n of list) {
        n.x = curX
        n.y = curY
        curY += n.h + gY
      }
      colBounds.set(c, { top: startY, bottom: curY - gY })
    }

    const gap = idx < colsSorted.length - 1 ? (gapAfterCol.get(c) ?? 120) : 0
    curX += (colWidth.get(c) || 200) + gap
  }

  const colTitles = colTitlesRaw
    .filter((ct) => colX.has(ct.col))
    .map((ct) => ({ col: ct.col, x: colX.get(ct.col) || padX, label: ct.label }))

  const width = Math.max(760, curX + padX)
  const height = Math.max(260, padTop + maxColH + padBottom)

  return { nodes, edges, colTitles, colBounds, width, height, sourceCount: touchedSources.size }
}

/** 计算边路径：跨列边自动绕行中间列下方，绝不穿越卡片 */
function computeEdgeGeometry(
  from: GraphNode,
  to: GraphNode,
  colBounds: Map<number, { top: number; bottom: number }>,
): { d: string; mx: number; my: number } {
  const x1 = from.x + from.w
  const y1 = from.y + from.h / 2
  const x2 = to.x
  const y2 = to.y + to.h / 2

  if (to.col - from.col > 1) {
    let maxBottom = Math.max(y1, y2)
    for (let c = from.col + 1; c < to.col; c++) {
      const b = colBounds.get(c)
      if (b && b.bottom > maxBottom) maxBottom = b.bottom
    }
    const routeY = maxBottom + 28
    const c1x = x1 + (x2 - x1) * 0.25
    const c2x = x1 + (x2 - x1) * 0.78
    const d = `M ${x1} ${y1} C ${c1x} ${routeY}, ${c2x} ${routeY}, ${x2} ${y2}`
    // 三次贝塞尔 t=0.5 处的精确点
    const mx = 0.125 * x1 + 0.375 * c1x + 0.375 * c2x + 0.125 * x2
    const my = 0.125 * y1 + 0.75 * routeY + 0.125 * y2
    return { d, mx, my }
  }

  const dx = Math.max(36, (x2 - x1) * 0.46)
  const d = `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
  return {
    d,
    mx: (x1 + x2) / 2,
    my: (y1 + y2) / 2,
  }
}

export default function Relations() {
  const [params, setParams] = useSearchParams()
  const reportCodeParam = params.get('report') || ''

  const [reports, setReports] = useState<any[]>([])
  const [datasources, setDatasources] = useState<any[]>([])
  const [endpoints, setEndpoints] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [syncStatuses, setSyncStatuses] = useState<any[]>([])
  const [rows, setRows] = useState<Rel[]>([])

  const [current, setCurrent] = useState(reportCodeParam)
  const [viewMode, setViewMode] = useState<'graph' | 'table'>('graph')
  const [zoom, setZoom] = useState<number | null>(null) // null = 自动适应画布
  const [stageWidth, setStageWidth] = useState(960)
  const stageRef = useRef<HTMLDivElement | null>(null)

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [hoverNodeId, setHoverNodeId] = useState<string | null>(null)

  const [edit, setEdit] = useState<Rel | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [pairs, setPairs] = useState<JoinPair[]>([{ leftField: '', rightField: '' }])
  const [cfg, setCfg] = useState<Record<string, any>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const dsLabel = (code: string) => datasets.find((x) => x.code === code)?.name || code
  const dsName = (code: string) => {
    const d = datasets.find((x) => x.code === code)
    return d ? `${d.name}（${d.code}）` : code
  }
  const epName = (code: string) => {
    const e = endpoints.find((x) => x.code === code)
    return e ? `${e.name}（${e.code}）` : code
  }

  useEffect(() => {
    const el = stageRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect?.width
      if (w && w > 200) setStageWidth(w)
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [viewMode])

  async function loadRelations(code: string) {
    if (!code) return
    setLoading(true)
    setSelectedNodeId(null)
    setSelectedEdgeId(null)
    try {
      setRows(await api.get<Rel[]>(`/api/admin/reports/${code}/relations`))
    } catch (err: any) {
      setError(err.message)
      setRows([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    Promise.all([
      api.get<any[]>('/api/admin/reports'),
      api.get<any[]>('/api/admin/datasources'),
      api.get<any[]>('/api/admin/endpoints'),
      api.get<any[]>('/api/admin/datasets'),
      api.get<any[]>('/api/sync/status').catch(() => []),
    ]).then(([r, ds, e, d, sync]) => {
      setReports(r || [])
      setDatasources(ds || [])
      setEndpoints(e || [])
      setDatasets(d || [])
      setSyncStatuses(sync || [])
      const fallback = reportCodeParam || r?.[0]?.code || ''
      setCurrent(fallback)
      if (fallback) loadRelations(fallback)
    }).catch((err) => {
      setError(err.message)
      setLoading(false)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (reportCodeParam && reportCodeParam !== current && reports.length > 0) {
      setCurrent(reportCodeParam)
      loadRelations(reportCodeParam)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportCodeParam])

  function selectReport(code: string) {
    setCurrent(code)
    setParams({ report: code }, { replace: true })
    setZoom(null) // 切换报表时自动适配视口
    loadRelations(code)
  }

  const currentReport = useMemo(() => reports.find((r) => r.code === current) || null, [reports, current])
  const isBuiltinScroll = current === 'rpt_contract_scroll'

  const graph = useMemo(
    () => buildLineageGraph(currentReport, rows, datasources, endpoints, datasets, syncStatuses),
    [currentReport, rows, datasources, endpoints, datasets, syncStatuses]
  )

  const fitScale = useMemo(() => {
    if (!graph.width || !stageWidth) return 1
    const raw = (stageWidth - 12) / graph.width
    return Math.max(0.62, Math.min(1, Number(raw.toFixed(3))))
  }, [graph.width, stageWidth])

  const effectiveZoom = zoom ?? fitScale

  const nodeMap = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph.nodes])

  const edgeGeoms = useMemo(() => {
    const map = new Map<string, { d: string; mx: number; my: number }>()
    for (const e of graph.edges) {
      const from = nodeMap.get(e.from)
      const to = nodeMap.get(e.to)
      if (from && to) {
        map.set(e.id, computeEdgeGeometry(from, to, graph.colBounds))
      }
    }
    return map
  }, [graph.edges, graph.colBounds, nodeMap])

  const focusNodeId = hoverNodeId || selectedNodeId
  const highlightedIds = useMemo(() => {
    if (!focusNodeId && !selectedEdgeId) return null
    const set = new Set<string>()
    if (focusNodeId) {
      set.add(focusNodeId)
      for (const e of graph.edges) {
        if (e.from === focusNodeId || e.to === focusNodeId) {
          set.add(e.id)
          set.add(e.from)
          set.add(e.to)
        }
      }
    }
    if (selectedEdgeId) {
      const e = graph.edges.find((x) => x.id === selectedEdgeId)
      if (e) {
        set.add(e.id)
        set.add(e.from)
        set.add(e.to)
      }
    }
    return set
  }, [focusNodeId, selectedEdgeId, graph.edges])

  const selectedNode = selectedNodeId ? nodeMap.get(selectedNodeId) || null : null
  const selectedEdge = selectedEdgeId ? graph.edges.find((e) => e.id === selectedEdgeId) || null : null

  const summary = useMemo(() => {
    const dsNodes = graph.nodes.filter((n) => n.kind === 'dataset')
    const totalRows = dsNodes.reduce((acc, n) => acc + Number(n.refData?.sync?.recordCount || 0), 0)
    const joinCnt = graph.edges.filter((e) => e.kind === 'join' || e.kind === 'builtin').length
    return { srcCnt: graph.sourceCount, dsCnt: dsNodes.length, totalRows, joinCnt }
  }, [graph])

  function openEdit(rel?: Rel) {
    setError('')
    if (rel) {
      setIsNew(false)
      setEdit({ ...rel })
      setPairs(parsePairs(rel.onFields))
      setCfg(parseCfg(rel.fetchConfig))
    } else {
      setIsNew(true)
      setEdit({
        code: '',
        reportCode: current,
        leftDataset: currentReport?.rootDataset || '',
        rightDataset: '',
        cardinality: 'N-1',
        joinType: 'left',
        onFields: '[{"leftField":"","rightField":""}]',
        fetchMode: 'dual_list',
        fetchConfig: '{}',
        expandMode: 'flat',
        sort: rows.length,
      })
      setPairs([{ leftField: '', rightField: '' }])
      setCfg({ rightEndpoint: endpoints[0]?.code || '' })
    }
  }

  function switchFetchMode(mode: string) {
    if (!edit) return
    setEdit({ ...edit, fetchMode: mode })
    if (mode === 'dual_list') {
      setCfg({ rightEndpoint: cfg.rightEndpoint || endpoints[0]?.code || '' })
    } else if (mode === 'child_batch') {
      setCfg({
        endpoint: cfg.endpoint || endpoints[0]?.code || '',
        keyParam: cfg.keyParam || 'ids',
        maxKeysPerCall: cfg.maxKeysPerCall || 200,
      })
    } else {
      setCfg({
        detailEndpoint: cfg.detailEndpoint || endpoints[0]?.code || '',
        pathParam: cfg.pathParam || 'id',
        concurrency: cfg.concurrency || 8,
      })
    }
  }

  async function save() {
    if (!edit) return
    if (!edit.code?.trim()) { setError('请填写关联编码（主键，保存后不可改）'); return }
    if (!edit.leftDataset || !edit.rightDataset) { setError('请选择左表和右表'); return }
    if (edit.joinType !== 'union' && !pairs.some((p) => p.leftField && p.rightField)) {
      setError('请至少填写一组关联字段')
      return
    }
    try {
      const onFields = JSON.stringify(pairs.filter((p) => p.leftField && p.rightField))
      const payload = { ...edit, onFields, fetchConfig: JSON.stringify(cfg) }
      await api.post('/api/admin/relations', payload)
      setEdit(null)
      await loadRelations(payload.reportCode)
      setCurrent(payload.reportCode)
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm(`删除关联关系「${code}」？`)) return
    await api.del(`/api/admin/relations/${code}`)
    setSelectedEdgeId(null)
    await loadRelations(current)
  }

  const modeHint = useMemo(
    () => FETCH_MODE.find((x) => x.value === edit?.fetchMode)?.hint || '',
    [edit?.fetchMode],
  )

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="relation" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>数据血缘与关联关系</h1>
            <p className="one-line">可视化查看每张报表的数据源、接口、同步数据集与关联链路；点击节点或连线查看详情</p>
          </div>
        </div>
        <div className="row">
          <div className="seg" role="group" aria-label="视图切换">
            <button
              type="button"
              className={`seg-item${viewMode === 'graph' ? ' active' : ''}`}
              onClick={() => setViewMode('graph')}
            >
              关系图
            </button>
            <button
              type="button"
              className={`seg-item${viewMode === 'table' ? ' active' : ''}`}
              onClick={() => setViewMode('table')}
            >
              配置明细
            </button>
          </div>
          {current && (
            <Link className="btn sm" to={`/reports/${current}`}>
              <Icon name="report" size={13} />查看报表
            </Link>
          )}
          {!isBuiltinScroll && (
            <button type="button" className="btn sm primary" onClick={() => openEdit()}>
              <Icon name="plus" size={13} />新建关联
            </button>
          )}
        </div>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      {/* 报表切换胶囊条 */}
      <div className="rel-report-bar" role="tablist" aria-label="选择报表">
        {reports.map((r) => (
          <button
            type="button"
            role="tab"
            aria-selected={r.code === current}
            key={r.code}
            className={`rel-report-pill${r.code === current ? ' active' : ''}`}
            onClick={() => selectReport(r.code)}
          >
            <span>{r.name}</span>
            {r.code === 'rpt_contract_scroll' && <em className="pill-tag">内置引擎</em>}
          </button>
        ))}
      </div>

      {viewMode === 'graph' ? (
        <div className="rel-workspace">
          {/* 左侧：交互式血缘关系图画布 */}
          <div className="rel-stage-card">
            <div className="rel-stage-toolbar">
              <div className="rel-legend">
                {(isBuiltinScroll || rows.length === 0) && (
                  <span className="rel-legend-item"><i className="dot src" />数据源</span>
                )}
                <span className="rel-legend-item"><i className="dot ds" />数据集 / 接口</span>
                {isBuiltinScroll && <span className="rel-legend-item"><i className="dot stage" />核算环节</span>}
                <span className="rel-legend-item"><i className="dot rpt" />报表输出</span>
                <span className="rel-legend-item">
                  <i className={`line ${isBuiltinScroll ? 'builtin' : 'join'}`} />
                  {isBuiltinScroll ? '核算链路' : '表关联条件'}
                </span>
              </div>
              <div className="rel-zoom-controls">
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => setZoom(Math.max(0.6, +(effectiveZoom - 0.1).toFixed(2)))}
                >−</button>
                <span className="mono rel-zoom-val">{Math.round(effectiveZoom * 100)}%</span>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => setZoom(Math.min(1.3, +(effectiveZoom + 0.1).toFixed(2)))}
                >+</button>
                <button
                  type="button"
                  className={`btn sm ghost${zoom == null ? ' active-fit' : ''}`}
                  onClick={() => setZoom(null)}
                >适应</button>
                <button
                  type="button"
                  className="btn sm ghost"
                  onClick={() => setZoom(1)}
                >100%</button>
              </div>
            </div>

            <div
              ref={stageRef}
              className="rel-canvas-scroll"
              onClick={() => {
                setSelectedNodeId(null)
                setSelectedEdgeId(null)
              }}
            >
              {loading && graph.nodes.length === 0 ? (
                <div style={{ padding: 24 }}><TableSkeleton rows={4} cols={4} /></div>
              ) : graph.nodes.length === 0 ? (
                <EmptyState variant="link" title="暂无血缘关系" desc="请选择一张报表查看其数据与接口关联图" />
              ) : (
                <div
                  className="rel-canvas-inner"
                  style={{
                    width: Math.ceil(graph.width * effectiveZoom),
                    height: Math.ceil(graph.height * effectiveZoom),
                  }}
                >
                  <div
                    className="rel-canvas-scaled"
                    style={{
                      width: graph.width,
                      height: graph.height,
                      transform: `scale(${effectiveZoom})`,
                      transformOrigin: '0 0',
                    }}
                  >
                    {/* 列分层标题 */}
                    {graph.colTitles.map((ct) => (
                      <div key={ct.col} className="rel-col-title" style={{ left: ct.x }}>
                        {ct.label}
                      </div>
                    ))}

                    {/* SVG 贝塞尔连线层 */}
                    <svg className="rel-svg-layer" width={graph.width} height={graph.height}>
                      <defs>
                        <marker id="rel-arrow-flow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                          <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#4f6b58" />
                        </marker>
                        <marker id="rel-arrow-join" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                          <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#5dff7a" />
                        </marker>
                        <marker id="rel-arrow-builtin" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
                          <path d="M 1 1.5 L 8.5 5 L 1 8.5 z" fill="#38bdf8" />
                        </marker>
                      </defs>

                      {graph.edges.map((e) => {
                        const geom = edgeGeoms.get(e.id)
                        if (!geom) return null
                        const isSelected = selectedEdgeId === e.id
                        const isDimmed = highlightedIds != null && !highlightedIds.has(e.id)
                        const stroke =
                          isSelected ? '#5dff7a'
                            : e.kind === 'join' ? '#5dff7a'
                              : e.kind === 'builtin' ? '#38bdf8'
                                : '#415a4a'
                        const marker =
                          e.kind === 'join' || isSelected ? 'url(#rel-arrow-join)'
                            : e.kind === 'builtin' ? 'url(#rel-arrow-builtin)'
                              : 'url(#rel-arrow-flow)'

                        return (
                          <g
                            key={e.id}
                            className={`rel-edge-g${isSelected ? ' selected' : ''}${isDimmed ? ' dimmed' : ''}`}
                            onClick={(ev) => {
                              ev.stopPropagation()
                              setSelectedEdgeId(e.id)
                              setSelectedNodeId(null)
                            }}
                          >
                            <path d={geom.d} fill="none" stroke="transparent" strokeWidth={14} />
                            <path
                              d={geom.d}
                              fill="none"
                              stroke={stroke}
                              strokeWidth={isSelected ? 2.4 : e.kind === 'flow' ? 1.5 : 2}
                              strokeDasharray={e.kind === 'flow' ? '4 4' : undefined}
                              markerEnd={marker}
                            />
                          </g>
                        )
                      })}
                    </svg>

                    {/* 边中点可点击标签 */}
                    {graph.edges.map((e) => {
                      if (!e.showPill || !e.label) return null
                      const geom = edgeGeoms.get(e.id)
                      if (!geom) return null
                      const isSelected = selectedEdgeId === e.id
                      const isDimmed = highlightedIds != null && !highlightedIds.has(e.id)
                      return (
                        <button
                          type="button"
                          key={`lbl:${e.id}`}
                          className={`rel-edge-pill kind-${e.kind}${isSelected ? ' selected' : ''}${isDimmed ? ' dimmed' : ''}`}
                          style={{ left: geom.mx, top: geom.my }}
                          title={e.detail || e.label}
                          onClick={(ev) => {
                            ev.stopPropagation()
                            setSelectedEdgeId(e.id)
                            setSelectedNodeId(null)
                          }}
                        >
                          <span className="pill-main">{e.label}</span>
                          {e.subLabel && <em>{e.subLabel}</em>}
                        </button>
                      )
                    })}

                    {/* HTML 节点卡片层 */}
                    {graph.nodes.map((n) => {
                      const isSelected = selectedNodeId === n.id
                      const isDimmed = highlightedIds != null && !highlightedIds.has(n.id)
                      return (
                        <button
                          type="button"
                          key={n.id}
                          className={`rel-node kind-${n.kind}${n.compact ? ' compact' : ''}${isSelected ? ' selected' : ''}${isDimmed ? ' dimmed' : ''}`}
                          style={{ left: n.x, top: n.y, width: n.w, height: n.h }}
                          onMouseEnter={() => setHoverNodeId(n.id)}
                          onMouseLeave={() => setHoverNodeId(null)}
                          onClick={(ev) => {
                            ev.stopPropagation()
                            setSelectedNodeId(n.id)
                            setSelectedEdgeId(null)
                          }}
                        >
                          <div className="rel-node-top">
                            <span className="rel-node-tag">
                              {n.sourceName ? `${n.sourceName} · ${n.tag || ''}` : (n.tag || n.kind)}
                            </span>
                            {n.badge && <span className={`rel-node-badge ${n.badgeTone || 'muted'}`}>{n.badge}</span>}
                          </div>
                          <div className="rel-node-title" title={n.title}>{n.title}</div>
                          {!n.compact && (
                            <>
                              <div className="rel-node-sub mono" title={n.subtitle}>{n.subtitle}</div>
                              {n.meta && <div className="rel-node-meta" title={n.meta}>{n.meta}</div>}
                            </>
                          )}
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* 右侧：节点 / 连线详情检查器 */}
          <aside className="card rel-inspector">
            {selectedEdge ? (
              <>
                <div className="rel-insp-head">
                  <span className={`badge ${selectedEdge.kind === 'join' ? 'ok' : 'muted'}`}>
                    {selectedEdge.kind === 'join' ? '可配置关联' : selectedEdge.kind === 'builtin' ? '内置核算链路' : '数据流转'}
                  </span>
                  <button type="button" className="btn sm ghost" onClick={() => setSelectedEdgeId(null)}>关闭</button>
                </div>
                <h3>{selectedEdge.label || '数据连线'}</h3>
                <p className="rel-insp-desc">
                  {nodeMap.get(selectedEdge.from)?.title} → {nodeMap.get(selectedEdge.to)?.title}
                </p>

                {selectedEdge.rel ? (
                  <>
                    <div className="rel-insp-kv">
                      <div><span>关联编码</span><b className="mono">{selectedEdge.rel.code}</b></div>
                      <div><span>左表</span><b>{dsName(selectedEdge.rel.leftDataset)}</b></div>
                      <div><span>右表</span><b>{dsName(selectedEdge.rel.rightDataset)}</b></div>
                      <div><span>连接方式</span><b>{labelOf(JOIN_TYPE, selectedEdge.rel.joinType)}</b></div>
                      <div><span>对应基数</span><b>{labelOf(CARDINALITY, selectedEdge.rel.cardinality)}</b></div>
                      <div><span>实时策略</span><b>{labelOf(FETCH_MODE, selectedEdge.rel.fetchMode)}</b></div>
                      <div><span>关联字段</span><b className="mono">{selectedEdge.label}</b></div>
                    </div>
                    <div className="row" style={{ marginTop: 14 }}>
                      <button type="button" className="btn sm primary" onClick={() => openEdit(selectedEdge.rel)}>编辑关联</button>
                      <button type="button" className="btn sm danger" onClick={() => remove(selectedEdge.rel!.code)}>删除</button>
                    </div>
                  </>
                ) : (
                  <p className="rel-insp-note">{selectedEdge.detail || '系统内部数据流转链路。'}</p>
                )}
              </>
            ) : selectedNode ? (
              <>
                <div className="rel-insp-head">
                  <span className="badge ok">{selectedNode.tag || selectedNode.kind}</span>
                  <button type="button" className="btn sm ghost" onClick={() => setSelectedNodeId(null)}>关闭</button>
                </div>
                <h3>{selectedNode.title}</h3>
                <div className="muted mono" style={{ fontSize: 12, marginBottom: 12 }}>{selectedNode.subtitle}</div>

                {selectedNode.kind === 'dataset' && !selectedNode.refData?.baseline && (
                  <>
                    <div className="rel-insp-kv">
                      <div><span>所属数据源</span><b>{selectedNode.refData?.src?.name || '—'}</b></div>
                      <div><span>绑定接口</span><b className="mono">{selectedNode.refData?.ep?.code || '—'}</b></div>
                      <div><span>接口路径</span><b className="mono">{selectedNode.refData?.ep ? `${selectedNode.refData.ep.method || 'GET'} ${selectedNode.refData.ep.path}` : '—'}</b></div>
                      <div><span>本地同步行数</span><b>{selectedNode.refData?.sync?.recordCount != null ? `${Number(selectedNode.refData.sync.recordCount).toLocaleString('zh-CN')} 行` : '未配置同步'}</b></div>
                      <div><span>同步方式</span><b>{selectedNode.refData?.sync?.mode === 'keys' ? '表级全量(keys)' : selectedNode.refData?.sync?.mode || '—'}</b></div>
                    </div>
                    <div className="row" style={{ marginTop: 14 }}>
                      <Link className="btn sm" to="/datasets">查看数据集</Link>
                      <Link className="btn sm" to="/sync">数据同步</Link>
                    </div>
                  </>
                )}

                {selectedNode.kind === 'dataset' && selectedNode.refData?.baseline && (
                  <>
                    <p className="rel-insp-note">{selectedNode.refData.desc}</p>
                    <div className="row" style={{ marginTop: 14 }}>
                      <Link className="btn sm" to="/reports/rpt_contract_scroll">前往维护基线</Link>
                    </div>
                  </>
                )}

                {selectedNode.kind === 'source' && (
                  <>
                    {selectedNode.refData?.baseline ? (
                      <p className="rel-insp-note">{selectedNode.refData.desc}</p>
                    ) : (
                      <div className="rel-insp-kv">
                        <div><span>服务地址</span><b className="mono">{selectedNode.refData?.src?.baseUrl || '—'}</b></div>
                        <div><span>认证方式</span><b>{selectedNode.refData?.src?.authType || '—'}</b></div>
                        <div><span>环境</span><b>{selectedNode.refData?.src?.env || 'prod'}</b></div>
                      </div>
                    )}
                    <div className="row" style={{ marginTop: 14 }}>
                      <Link className="btn sm" to={selectedNode.refData?.baseline ? '/reports/rpt_contract_scroll' : '/datasources'}>
                        {selectedNode.refData?.baseline ? '前往维护基线' : '查看数据源'}
                      </Link>
                    </div>
                  </>
                )}

                {selectedNode.kind === 'stage' && (
                  <p className="rel-insp-note">{selectedNode.refData?.desc}</p>
                )}

                {selectedNode.kind === 'report' && (
                  <>
                    <p className="rel-insp-note">{selectedNode.refData?.report?.description || '点击下方按钮直接进入报表查询与导出。'}</p>
                    <div className="row" style={{ marginTop: 14 }}>
                      <Link className="btn sm primary" to={`/reports/${selectedNode.refData?.report?.code}`}>打开此报表</Link>
                    </div>
                  </>
                )}
              </>
            ) : (
              <>
                <div className="rel-insp-head">
                  <span className="badge ok">{isBuiltinScroll ? '内置核算引擎' : rows.length > 0 ? '多表关联报表' : '单表报表'}</span>
                </div>
                <h3>{currentReport?.name || '请选择报表'}</h3>
                <p className="rel-insp-note">
                  {currentReport?.description || '点击画布中任意数据源、接口数据集或连线，查看对应字段关联与同步行数详情。'}
                </p>
                <div className="rel-insp-kv">
                  <div><span>涉及数据源</span><b>{summary.srcCnt} 个</b></div>
                  <div><span>关联数据集</span><b>{summary.dsCnt} 张</b></div>
                  <div><span>关联 / 核算边</span><b>{summary.joinCnt} 条</b></div>
                  <div><span>本地同步总行</span><b>{summary.totalRows.toLocaleString('zh-CN')} 行</b></div>
                </div>
                {isBuiltinScroll && (
                  <div className="rel-insp-callout">
                    《销售合同滚动统计报表》由后端内置核算引擎驱动，自动整合 10 月期初基线与 9 张 U9 业务表，无需手工配置关联边。
                  </div>
                )}
              </>
            )}
          </aside>
        </div>
      ) : (
        <div className="table-wrap rel-detail-table">
          <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
            <thead>
              <tr>
                <th>关联编码</th>
                <th>左表</th>
                <th>右表</th>
                <th>基数 / 连接</th>
                <th>取数策略</th>
                <th>关联字段</th>
                <th style={{ width: 124 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const ps = parsePairs(r.onFields)
                return (
                  <tr key={r.code}>
                    <td>
                      <div style={{ fontWeight: 600 }} className="mono">{r.code}</div>
                      <div className="muted" style={{ fontSize: 11 }}>{currentReport?.name || r.reportCode}</div>
                    </td>
                    <td>
                      <div>{dsLabel(r.leftDataset)}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>{r.leftDataset}</div>
                    </td>
                    <td>
                      <div>{dsLabel(r.rightDataset)}</div>
                      <div className="muted mono" style={{ fontSize: 11 }}>{r.rightDataset}</div>
                    </td>
                    <td>
                      <span className="badge muted">{labelOf(JOIN_TYPE, r.joinType)} · {r.cardinality}</span>
                    </td>
                    <td><span className="badge ok">{labelOf(FETCH_MODE, r.fetchMode)}</span></td>
                    <td className="mono" style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {ps.map((p) => `${p.leftField} = ${p.rightField}`).join(' & ') || '-'}
                    </td>
                    <td>
                      <div className="row" style={{ flexWrap: 'nowrap' }}>
                        <button type="button" className="btn sm" onClick={() => openEdit(r)}>编辑</button>
                        <button type="button" className="btn sm danger" onClick={() => remove(r.code)}>删除</button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {loading && rows.length === 0 ? <TableSkeleton rows={5} cols={5} /> : null}
          {rows.length === 0 && !loading && (
            <EmptyState
              variant="link"
              title={isBuiltinScroll ? '该报表由内置核算引擎驱动' : '当前为单表报表，暂无外部关联'}
              desc={isBuiltinScroll ? '请切换至「关系图」查看完整的 10 表核算血缘链路' : '可点击右上角「新建关联」接入其他表'}
            />
          )}
        </div>
      )}

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建关联' : '编辑关联'}</h3>
                <p className="sub">左右表如何对齐、如何取数</p>
              </div>
              <button type="button" className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>基本信息</b>
                  <span>归属与两端数据集</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      关联编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 rel_cr_hang"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>所属报表</label>
                    <select
                      className="select"
                      value={edit.reportCode}
                      onChange={(e) => setEdit({ ...edit, reportCode: e.target.value })}
                    >
                      {reports.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
                    </select>
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>左表（已有结果 / 主表侧）<span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.leftDataset}
                      onChange={(e) => setEdit({ ...edit, leftDataset: e.target.value })}
                    >
                      <option value="">请选择数据集</option>
                      {datasets.map((d) => (
                        <option key={d.code} value={d.code}>{dsName(d.code)}</option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label>右表（要关联进来）<span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.rightDataset}
                      onChange={(e) => setEdit({ ...edit, rightDataset: e.target.value })}
                    >
                      <option value="">请选择数据集</option>
                      {datasets.map((d) => (
                        <option key={d.code} value={d.code}>{dsName(d.code)}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>对应关系</b>
                  <span>会不会摊成多行</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>基数</label>
                    <select
                      className="select"
                      value={edit.cardinality}
                      onChange={(e) => setEdit({ ...edit, cardinality: e.target.value })}
                    >
                      {CARDINALITY.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label>连接方式</label>
                    <select
                      className="select"
                      value={edit.joinType}
                      onChange={(e) => setEdit({ ...edit, joinType: e.target.value })}
                    >
                      {JOIN_TYPE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>一对多时如何展开</label>
                    <select
                      className="select"
                      value={edit.expandMode}
                      onChange={(e) => setEdit({ ...edit, expandMode: e.target.value })}
                    >
                      {EXPAND_MODE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                    <span className="hint">{EXPAND_MODE.find((x) => x.value === edit.expandMode)?.hint}</span>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>关联字段</b>
                  <span>左右字段值相等则匹配，可多组</span>
                  <button
                    type="button"
                    className="btn sm"
                    style={{ marginLeft: 'auto' }}
                    onClick={() => setPairs([...pairs, { leftField: '', rightField: '' }])}
                  >
                    <Icon name="plus" size={12} />加一组
                  </button>
                </div>
                {pairs.map((p, idx) => (
                  <div className="param-row" key={idx} style={{ gridTemplateColumns: '1fr 28px 1fr auto' }}>
                    <input
                      className="input"
                      placeholder="左表字段 hang_no"
                      value={p.leftField}
                      onChange={(e) => setPairs(pairs.map((x, i) => (i === idx ? { ...x, leftField: e.target.value } : x)))}
                    />
                    <span className="muted">=</span>
                    <input
                      className="input"
                      placeholder="右表字段 doc_no"
                      value={p.rightField}
                      onChange={(e) => setPairs(pairs.map((x, i) => (i === idx ? { ...x, rightField: e.target.value } : x)))}
                    />
                    <button
                      type="button"
                      className="btn sm danger"
                      disabled={pairs.length <= 1}
                      onClick={() => setPairs(pairs.filter((_, i) => i !== idx))}
                    >
                      删
                    </button>
                  </div>
                ))}
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>取数策略</b>
                  <span>{modeHint}</span>
                </div>
                <div className="field">
                  <label>如何拉右表数据</label>
                  <select
                    className="select"
                    value={edit.fetchMode}
                    onChange={(e) => switchFetchMode(e.target.value)}
                  >
                    {FETCH_MODE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                  </select>
                </div>

                {edit.fetchMode === 'dual_list' && (
                  <div className="field">
                    <label>右表列表接口</label>
                    <select
                      className="select"
                      value={cfg.rightEndpoint || ''}
                      onChange={(e) => setCfg({ ...cfg, rightEndpoint: e.target.value })}
                    >
                      <option value="">请选择接口</option>
                      {endpoints.map((ep) => (
                        <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                      ))}
                    </select>
                  </div>
                )}

                {edit.fetchMode === 'child_batch' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>批量查询接口</label>
                      <select
                        className="select"
                        value={cfg.endpoint || ''}
                        onChange={(e) => setCfg({ ...cfg, endpoint: e.target.value })}
                      >
                        <option value="">请选择接口</option>
                        {endpoints.map((ep) => (
                          <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                        ))}
                      </select>
                    </div>
                    <div className="field">
                      <label>批量键参数名</label>
                      <input
                        className="input"
                        value={cfg.keyParam || 'ids'}
                        onChange={(e) => setCfg({ ...cfg, keyParam: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>单次最多键数</label>
                      <input
                        className="input"
                        type="number"
                        value={cfg.maxKeysPerCall || 200}
                        onChange={(e) => setCfg({ ...cfg, maxKeysPerCall: Number(e.target.value) })}
                      />
                    </div>
                  </div>
                )}

                {edit.fetchMode === 'child_lookup' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>单条查询接口</label>
                      <select
                        className="select"
                        value={cfg.detailEndpoint || cfg.endpoint || ''}
                        onChange={(e) => setCfg({ ...cfg, detailEndpoint: e.target.value })}
                      >
                        <option value="">请选择接口</option>
                        {endpoints.map((ep) => (
                          <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                        ))}
                      </select>
                    </div>
                    <div className="field">
                      <label>路径参数名</label>
                      <input
                        className="input"
                        placeholder="id"
                        value={cfg.pathParam || 'id'}
                        onChange={(e) => setCfg({ ...cfg, pathParam: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>并发数</label>
                      <input
                        className="input"
                        type="number"
                        min={1}
                        max={32}
                        value={cfg.concurrency || 8}
                        onChange={(e) => setCfg({ ...cfg, concurrency: Number(e.target.value) })}
                      />
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="modal-footer">
              <button type="button" className="btn" onClick={() => setEdit(null)}>取消</button>
              <button type="button" className="btn primary" onClick={save}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
