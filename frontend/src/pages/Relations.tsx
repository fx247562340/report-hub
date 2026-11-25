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

type BuiltinStage = {
  id: string
  tag: string
  title: string
  subtitle: string
  meta: string
  desc: string
}

type BuiltinInput = {
  dsCode: string
  role: string
  stageId: string
  edgeLabel: string
  detail: string
}

type BuiltinLineage = {
  engineLabel: string
  outputBadge: string
  outputMeta: string
  callout: string
  baselineTable: string
  baselineTitle: string
  baselineTag: string
  baselineBadge: string
  baselineDesc: string
  baselineStageId: string
  baselineEdgeLabel: string
  baselineEdgeDetail: string
  stages: BuiltinStage[]
  inputs: BuiltinInput[]
}

type LineagePayload = {
  reportCode: string
  relations: Rel[]
  builtin: BuiltinLineage | null
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

const CARDINALITY: Record<string, string> = {
  '1-1': '一对一',
  '1-N': '一对多',
  'N-1': '多对一',
  'N-N': '多对多',
}
const JOIN_TYPE: Record<string, string> = {
  left: '左连接',
  inner: '内连接',
  union: '并集',
}
const FETCH_MODE: Record<string, string> = {
  dual_list: '双侧列表再关联',
  child_batch: '批量 IN 查询',
  child_lookup: '逐条单查',
}

function parsePairs(json: string): JoinPair[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr)
      ? arr.map((x) => ({ leftField: x.leftField || '', rightField: x.rightField || '' }))
      : []
  } catch {
    return []
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
  lineage: LineagePayload | null,
  datasources: any[],
  endpoints: any[],
  datasets: any[],
  syncStatuses: any[],
): LayoutResult {
  if (!report) {
    return { nodes: [], edges: [], colTitles: [], colBounds: new Map(), width: 860, height: 320, sourceCount: 0 }
  }

  const relations = lineage?.relations || []
  const builtin = lineage?.builtin || null

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

  let colTitlesRaw: { col: number; label: string }[] = []
  const gapAfterCol = new Map<number, number>()

  if (builtin) {
    // 后端内置核算引擎血缘图（数据源 -> 同步表/基线表 -> 核算环节 -> 报表输出）
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

    pushNode({
      id: 'tbl:baseline',
      kind: 'dataset',
      col: 1,
      compact: true,
      w: 212,
      h: 54,
      title: builtin.baselineTitle,
      subtitle: builtin.baselineTable,
      tag: builtin.baselineTag,
      badge: builtin.baselineBadge,
      badgeTone: 'ok',
      refData: {
        baseline: true,
        desc: builtin.baselineDesc,
      },
    })
    pushEdge({
      id: 'e:src:pg_baseline->tbl:baseline',
      from: 'src:pg_baseline',
      to: 'tbl:baseline',
      kind: 'flow',
      showPill: false,
      detail: '从本地 PostgreSQL 读取期初基线行',
    })

    for (const sp of builtin.inputs) {
      makeDatasetNode(sp.dsCode, 1, {
        tag: sp.role,
        compact: true,
        w: 212,
        linkSourceNode: true,
      })
    }

    for (const st of builtin.stages) {
      pushNode({
        id: st.id,
        kind: 'stage',
        col: 2,
        title: st.title,
        subtitle: st.subtitle,
        tag: st.tag,
        meta: st.meta,
        w: 204,
        h: 82,
        refData: { desc: st.desc },
      })
    }

    pushEdge({
      id: `e:tbl:baseline->${builtin.baselineStageId}`,
      from: 'tbl:baseline',
      to: builtin.baselineStageId,
      label: builtin.baselineEdgeLabel,
      kind: 'builtin',
      showPill: true,
      detail: builtin.baselineEdgeDetail,
    })

    for (const sp of builtin.inputs) {
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
      tag: builtin.engineLabel,
      badge: builtin.outputBadge,
      badgeTone: 'ok',
      meta: builtin.outputMeta,
      w: 204,
      h: 92,
      refData: { report },
    })

    for (const st of builtin.stages) {
      pushEdge({
        id: `e:${st.id}->${rptId}`,
        from: st.id,
        to: rptId,
        label: '汇总输出',
        kind: 'flow',
        showPill: false,
        detail: `汇总输出至《${report.name}》`,
      })
    }

    gapAfterCol.set(0, 62)
    gapAfterCol.set(1, 134)
    gapAfterCol.set(2, 68)

    colTitlesRaw = [
      { col: 0, label: '数据源' },
      { col: 1, label: `同步数据集 / 基线表（${builtin.inputs.length + 1}）` },
      { col: 2, label: '内置核算链路' },
      { col: 3, label: '报表输出' },
    ]
  } else if (relations.length === 0) {
    // 单表报表：数据源 -> 主数据集 / 接口 -> 报表输出
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
      tag: '内置 · 单表聚合',
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
    // 多表关联报表：数据源徽标内嵌在数据集卡片顶部，按关联深度自动排布
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
      const joinTag = `${JOIN_TYPE[rel.joinType] || rel.joinType} · ${rel.cardinality}`
      pushEdge({
        id: `rel:${rel.code}`,
        from: `ds:${rel.leftDataset}`,
        to: `ds:${rel.rightDataset}`,
        label: onStr || rel.code,
        subLabel: joinTag,
        kind: 'join',
        rel,
        showPill: true,
        detail: `${JOIN_TYPE[rel.joinType] || rel.joinType}（${CARDINALITY[rel.cardinality] || rel.cardinality}），实时取数策略：${FETCH_MODE[rel.fetchMode] || rel.fetchMode}`,
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
      tag: '内置 · 多表关联',
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

  // 自动坐标布局
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

    if (builtin && c === 0 && list.length === 2) {
      const topNode = list[0]
      const u9Node = list[1]
      topNode.x = curX
      topNode.y = padTop
      u9Node.x = curX
      u9Node.y = padTop + 54 + 8 + Math.round(((maxColH - 62) - u9Node.h) / 2)
      colBounds.set(c, { top: topNode.y, bottom: u9Node.y + u9Node.h })
    } else if (builtin && c === 2 && list.length === 3) {
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

/** 跨列边自动沿中间列底部弧线绕行，绝不穿越卡片 */
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
  const [lineage, setLineage] = useState<LineagePayload | null>(null)

  const [current, setCurrent] = useState(reportCodeParam)
  const [zoom, setZoom] = useState<number | null>(null) // null = 自动适应画布
  const [stageWidth, setStageWidth] = useState(960)
  const stageRef = useRef<HTMLDivElement | null>(null)

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [hoverNodeId, setHoverNodeId] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const dsName = (code: string) => {
    const d = datasets.find((x) => x.code === code)
    return d ? `${d.name}（${d.code}）` : code
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
  }, [])

  async function loadLineage(code: string) {
    if (!code) return
    setLoading(true)
    setSelectedNodeId(null)
    setSelectedEdgeId(null)
    try {
      setLineage(await api.get<LineagePayload>(`/api/admin/reports/${code}/lineage`))
    } catch (err: any) {
      setError(err.message)
      setLineage({ reportCode: code, relations: [], builtin: null })
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
      if (fallback) loadLineage(fallback)
    }).catch((err) => {
      setError(err.message)
      setLoading(false)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (reportCodeParam && reportCodeParam !== current && reports.length > 0) {
      setCurrent(reportCodeParam)
      loadLineage(reportCodeParam)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportCodeParam])

  function selectReport(code: string) {
    setCurrent(code)
    setParams({ report: code }, { replace: true })
    setZoom(null)
    loadLineage(code)
  }

  const currentReport = useMemo(() => reports.find((r) => r.code === current) || null, [reports, current])
  const isBuiltinEngine = Boolean(lineage?.builtin)
  const relationCount = lineage?.relations?.length || 0

  const graph = useMemo(
    () => buildLineageGraph(currentReport, lineage, datasources, endpoints, datasets, syncStatuses),
    [currentReport, lineage, datasources, endpoints, datasets, syncStatuses]
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

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="relation" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>数据血缘与关联关系</h1>
            <p className="one-line">全部报表均由后端内置引擎统一维护，此处只读展示各报表的数据源、接口与表间血缘链路</p>
          </div>
        </div>
        <div className="row">
          <span className="pill" title="全部报表关联规则已收归后端代码维护，不再依赖数据库配置表">
            全部内置引擎 · 只读血缘
          </span>
          {current && (
            <Link className="btn sm primary" to={`/reports/${current}`}>
              <Icon name="report" size={13} />查看报表
            </Link>
          )}
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

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
          </button>
        ))}
      </div>

      <div className="rel-workspace">
        {/* 左侧：交互式血缘关系图画布 */}
        <div className="rel-stage-card">
          <div className="rel-stage-toolbar">
            <div className="rel-legend">
              {(isBuiltinEngine || relationCount === 0) && (
                <span className="rel-legend-item"><i className="dot src" />数据源</span>
              )}
              <span className="rel-legend-item"><i className="dot ds" />数据集 / 接口</span>
              {isBuiltinEngine && <span className="rel-legend-item"><i className="dot stage" />核算环节</span>}
              <span className="rel-legend-item"><i className="dot rpt" />报表输出</span>
              {isBuiltinEngine ? (
                <span className="rel-legend-item"><i className="line builtin" />内置核算链路</span>
              ) : relationCount > 0 ? (
                <span className="rel-legend-item"><i className="line join" />内置关联规则</span>
              ) : null}
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
                  {selectedEdge.kind === 'join' ? '内置关联规则' : selectedEdge.kind === 'builtin' ? '内置核算链路' : '数据流转'}
                </span>
                <button type="button" className="btn sm ghost" onClick={() => setSelectedEdgeId(null)}>关闭</button>
              </div>
              <h3>{selectedEdge.label || '数据连线'}</h3>
              <p className="rel-insp-desc">
                {nodeMap.get(selectedEdge.from)?.title} → {nodeMap.get(selectedEdge.to)?.title}
              </p>

              {selectedEdge.rel ? (
                <div className="rel-insp-kv">
                  <div><span>规则标识</span><b className="mono">{selectedEdge.rel.code}</b></div>
                  <div><span>左表</span><b>{dsName(selectedEdge.rel.leftDataset)}</b></div>
                  <div><span>右表</span><b>{dsName(selectedEdge.rel.rightDataset)}</b></div>
                  <div><span>连接方式</span><b>{JOIN_TYPE[selectedEdge.rel.joinType] || selectedEdge.rel.joinType}</b></div>
                  <div><span>对应基数</span><b>{CARDINALITY[selectedEdge.rel.cardinality] || selectedEdge.rel.cardinality}</b></div>
                  <div><span>实时策略</span><b>{FETCH_MODE[selectedEdge.rel.fetchMode] || selectedEdge.rel.fetchMode}</b></div>
                  <div><span>关联字段</span><b className="mono">{selectedEdge.label}</b></div>
                </div>
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
                <span className="badge ok">
                  {isBuiltinEngine ? '内置 · 滚动核算引擎' : relationCount > 0 ? '内置 · 多表关联引擎' : '内置 · 单表聚合引擎'}
                </span>
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
              <div className="rel-insp-callout">
                {lineage?.builtin?.callout || '该报表的表间关联与聚合规则已内置在后端代码中统一维护，不再落库配置，此处仅做只读数据血缘展示。'}
              </div>
            </>
          )}
        </aside>
      </div>
    </>
  )
}
