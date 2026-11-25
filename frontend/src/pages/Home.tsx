import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, getUser } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'

type ExecKpi = {
  key: string
  label: string
  value: number
  prevValue: number | null
  deltaPct: number | null
  unit: string
  kind: 'money' | 'ton'
  positiveIsGood: boolean | null
  sub: string
  drillUrl: string
}

type MonthlyPoint = {
  month: string
  label: string
  blackIn: number
  blackOut: number
  whiteIn: number
  whiteShip: number
  invoiceAmt: number
  paymentAmt: number
  totalDebt: number
}

type TopDebtCustomer = {
  customerName: string
  totalDebt: number
  invUnpaid: number
  shipUninv: number
  contractCount: number
  sharePct: number
}

type StockAlert = {
  contractNo: string
  customerName: string
  productName: string
  blackStock: number
  whiteStock: number
}

type OverviewData = {
  queryMode: string
  generatedAt?: string
  syncHealth?: {
    total: number
    enabled: number
    failed: number
    running: number
    lastSyncedAt?: string | null
    failedDatasets?: string[]
  }
  executive?: {
    error?: string
    month: string
    monthLabel: string
    prevMonth?: string | null
    availableMonths: string[]
    contractCount: number
    kpis: ExecKpi[]
    monthlySeries: MonthlyPoint[]
    topDebtCustomers: TopDebtCustomer[]
    stockAlerts: StockAlert[]
    stockAlertTotal: number
    lastSyncedAt?: string | null
  }
}

function fmtSmartKpi(v: number, kind: 'money' | 'ton'): { main: string; unit: string; exact: string } {
  if (kind === 'money') {
    const exact = `${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} 元`
    if (Math.abs(v) >= 10000) {
      return {
        main: (v / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
        unit: '万元',
        exact,
      }
    }
    return {
      main: v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
      unit: '元',
      exact,
    }
  }
  return {
    main: v.toLocaleString('zh-CN', { maximumFractionDigits: 2 }),
    unit: '吨',
    exact: `${v.toLocaleString('zh-CN', { maximumFractionDigits: 4 })} 吨`,
  }
}

function fmtWanShort(v: number) {
  if (Math.abs(v) >= 10000) {
    return `${(v / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}万`
  }
  return v.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function fmtSyncTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function MonthlyTrendChart({
  series,
  activeMonth,
  mode,
  onSelectMonth,
}: {
  series: MonthlyPoint[]
  activeMonth: string
  mode: 'finance' | 'tonnage'
  onSelectMonth: (m: string) => void
}) {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null)

  const keys = mode === 'finance'
    ? [
        { key: 'invoiceAmt' as const, label: '开票金额', color: '#5dff7a' },
        { key: 'paymentAmt' as const, label: '回款金额', color: '#38bdf8' },
      ]
    : [
        { key: 'blackIn' as const, label: '黑件来料', color: '#94a3b8' },
        { key: 'whiteIn' as const, label: '白件入库', color: '#5dff7a' },
        { key: 'whiteShip' as const, label: '白件发货', color: '#fbbf24' },
      ]

  const maxVal = useMemo(() => {
    let m = 0
    for (const pt of series) {
      for (const k of keys) {
        const v = Number(pt[k.key] || 0)
        if (v > m) m = v
      }
    }
    return m > 0 ? m * 1.15 : 100
  }, [series, mode])

  if (!series.length) {
    return <EmptyState compact variant="data" title="暂无月度趋势数据" desc="完成 U9 同步后将自动呈现近 6 个月趋势" />
  }

  const W = 560
  const H = 236
  const padL = 56
  const padR = 16
  const padT = 16
  const padB = 34
  const chartW = W - padL - padR
  const chartH = H - padT - padB
  const slotW = chartW / Math.max(series.length, 1)
  const barGroupW = Math.min(slotW * 0.62, keys.length * 26)
  const barGap = 5
  const barW = Math.max(10, (barGroupW - barGap * (keys.length - 1)) / keys.length)

  const ticks = [0, 0.25, 0.5, 0.75, 1]
  const activePoint = hoverIdx != null
    ? series[hoverIdx]
    : (series.find((p) => p.month === activeMonth) || series[series.length - 1])

  return (
    <div className="dash-chart-wrap">
      <div className="dash-chart-legend">
        {keys.map((k) => (
          <span key={k.key} className="legend-item">
            <i style={{ background: k.color }} />
            {k.label}
          </span>
        ))}
        <span className="legend-tip">悬浮查看明细 · 点击柱体切换月份</span>
      </div>

      {activePoint && (
        <div className="dash-chart-readout">
          <span className="readout-month">{activePoint.month}</span>
          {keys.map((k) => {
            const raw = Number(activePoint[k.key] || 0)
            return (
              <span className="readout-item" key={k.key}>
                <i style={{ background: k.color }} />
                <span>{k.label}</span>
                <b>
                  {mode === 'finance'
                    ? `${raw.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} 元`
                    : `${raw.toLocaleString('zh-CN', { maximumFractionDigits: 2 })} 吨`}
                </b>
              </span>
            )
          })}
        </div>
      )}

      <div className="dash-chart-stage">
        <svg viewBox={`0 0 ${W} ${H}`} className="dash-svg" role="img" aria-label="月度经营趋势图">
          {ticks.map((t) => {
            const y = padT + chartH * (1 - t)
            const val = maxVal * t
            return (
              <g key={t}>
                <line x1={padL} y1={y} x2={W - padR} y2={y} className="chart-grid" />
                <text x={padL - 8} y={y + 4} textAnchor="end" className="chart-axis-text">
                  {mode === 'finance' ? fmtWanShort(val) : Math.round(val).toLocaleString('zh-CN')}
                </text>
              </g>
            )
          })}

          {series.map((pt, idx) => {
            const cx = padL + slotW * idx + slotW / 2
            const startX = cx - barGroupW / 2
            const isActive = pt.month === activeMonth
            const isHover = hoverIdx === idx
            return (
              <g
                key={pt.month}
                className="chart-col"
                onMouseEnter={() => setHoverIdx(idx)}
                onMouseLeave={() => setHoverIdx(null)}
                onClick={() => onSelectMonth(pt.month)}
              >
                <rect
                  x={padL + slotW * idx + 4}
                  y={padT - 4}
                  width={Math.max(20, slotW - 8)}
                  height={chartH + 28}
                  rx={8}
                  className={`chart-slot-bg${isActive ? ' active' : ''}${isHover ? ' hover' : ''}`}
                />
                {keys.map((k, ki) => {
                  const val = Math.max(0, Number(pt[k.key] || 0))
                  const h = Math.max(val > 0 ? 3 : 0, (val / maxVal) * chartH)
                  const x = startX + ki * (barW + barGap)
                  const y = padT + chartH - h
                  return (
                    <rect
                      key={k.key}
                      x={x}
                      y={y}
                      width={barW}
                      height={h}
                      rx={4}
                      fill={k.color}
                      opacity={isActive || isHover ? 1 : 0.72}
                    />
                  )
                })}
                <text
                  x={cx}
                  y={H - 12}
                  textAnchor="middle"
                  className={`chart-x-text${isActive ? ' active' : ''}`}
                >
                  {pt.month.slice(2)}
                </text>
              </g>
            )
          })}
        </svg>
      </div>
    </div>
  )
}

const REALTIME_STAGES = [
  '连接用友 U9 Cloud',
  '拉取销售订单与来料',
  '拉取入库与发货单据',
  '核算应收开票与核销',
]

function DashboardLoadingView({
  isRealtime,
  elapsedSec,
  isAdmin,
  switchingMode,
  onSwitchLocal,
}: {
  isRealtime: boolean
  elapsedSec: number
  isAdmin: boolean
  switchingMode: boolean
  onSwitchLocal: () => void
}) {
  const activeStage = Math.min(
    REALTIME_STAGES.length - 1,
    elapsedSec < 2 ? 0 : elapsedSec < 6 ? 1 : elapsedSec < 12 ? 2 : 3
  )

  return (
    <div className="dash-loading-wrap" aria-busy="true" aria-live="polite">
      <div className={`dash-load-banner${isRealtime ? ' realtime' : ''}`}>
        <div className="dash-load-top">
          <div className="dash-load-left">
            <span className="dash-load-spinner" aria-hidden />
            <div>
              <div className="dash-load-title">
                {isRealtime ? '正在实时聚合 U9 经营数据…' : '正在加载经营看板数据…'}
                <span className="dash-load-timer mono">已等待 {elapsedSec.toFixed(1)}s</span>
              </div>
              <div className="dash-load-sub">
                {isRealtime
                  ? '实时聚合模式会并发拉取 U9 Cloud 9 张业务单据并滚动核算多月指标，通常需要 10 – 25 秒，请稍候。'
                  : '正在从本地同步库读取并汇总最新月度指标…'}
              </div>
            </div>
          </div>
          {isRealtime && isAdmin && (
            <button
              type="button"
              className="btn sm primary"
              disabled={switchingMode}
              onClick={onSwitchLocal}
            >
              <Icon name="bolt" size={13} />
              {switchingMode ? '切换中…' : '切回本地数据（秒开）'}
            </button>
          )}
        </div>

        {isRealtime && (
          <div className="dash-load-stages">
            {REALTIME_STAGES.map((label, idx) => {
              const state = idx < activeStage ? 'done' : idx === activeStage ? 'active' : 'pending'
              return (
                <div key={label} className={`dash-load-stage ${state}`}>
                  <span className="dash-stage-dot">
                    {state === 'done' ? <Icon name="check" size={10} /> : idx + 1}
                  </span>
                  <span>{label}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>

      {/* 全宽 5 列 KPI 骨架（与真实布局 1:1 对齐） */}
      <section className="dash-kpi-grid" aria-hidden>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="dash-kpi-card dash-kpi-skel">
            <div className="dash-kpi-top">
              <span className="skel" style={{ width: '48%', height: 12 }} />
              <span className="skel" style={{ width: 42, height: 18, borderRadius: 999 }} />
            </div>
            <div className="dash-kpi-val" style={{ marginTop: 4 }}>
              <span className="skel" style={{ width: '62%', height: 28, borderRadius: 8 }} />
            </div>
            <div className="dash-kpi-foot">
              <span className="skel" style={{ width: '74%', height: 11 }} />
            </div>
          </div>
        ))}
      </section>

      {/* 全宽双栏主体骨架（趋势图 + 排行榜） */}
      <section className="dash-bento" aria-hidden>
        <div className="card dash-panel dash-skel-panel">
          <div className="dash-panel-head">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1 }}>
              <span className="skel" style={{ width: 120, height: 16 }} />
              <span className="skel" style={{ width: 220, height: 11 }} />
            </div>
            <span className="skel" style={{ width: 132, height: 30, borderRadius: 10 }} />
          </div>
          <div className="dash-skel-chart">
            {[46, 72, 58, 86, 64, 92].map((pct, i) => (
              <div key={i} className="dash-skel-bar-col">
                <div className="dash-skel-bar-pair">
                  <span className="skel" style={{ width: 18, height: `${pct}%`, borderRadius: 5 }} />
                  <span className="skel" style={{ width: 18, height: `${Math.max(24, pct - 18)}%`, borderRadius: 5 }} />
                </div>
                <span className="skel" style={{ width: 32, height: 10 }} />
              </div>
            ))}
          </div>
        </div>

        <div className="card dash-panel dash-skel-panel">
          <div className="dash-panel-head">
            <span className="skel" style={{ width: 170, height: 28, borderRadius: 8 }} />
            <span className="skel" style={{ width: 64, height: 26, borderRadius: 8 }} />
          </div>
          <div className="dash-skel-ranks">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="dash-skel-rank-row">
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                  <span className="skel" style={{ width: `${58 - i * 4}%`, height: 13 }} />
                  <span className="skel" style={{ width: 64, height: 13 }} />
                </div>
                <span className="skel" style={{ width: `${88 - i * 12}%`, height: 6, borderRadius: 999 }} />
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  )
}

export default function Home() {
  const user = getUser()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'admin'
  const displayName = user?.displayName || user?.username || '朋友'

  const [overview, setOverview] = useState<OverviewData | null>(null)
  const [liveMode, setLiveMode] = useState<string>('')
  const [selectedMonth, setSelectedMonth] = useState<string>('')
  const [overviewLoading, setOverviewLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [switchingMode, setSwitchingMode] = useState(false)
  const [elapsedSec, setElapsedSec] = useState(0)
  const [error, setError] = useState('')

  const [chartMode, setChartMode] = useState<'finance' | 'tonnage'>('finance')
  const [focusTab, setFocusTab] = useState<'debt' | 'alerts'>('debt')

  const loadOverview = useCallback(async (month?: string, force = false) => {
    if (force) setRefreshing(true)
    else setOverviewLoading(true)
    setError('')
    try {
      const qs = new URLSearchParams()
      if (month) qs.set('month', month)
      if (force) qs.set('refresh', 'true')
      const q = qs.toString() ? `?${qs.toString()}` : ''
      const data = await api.get<OverviewData>(`/api/dashboard/overview${q}`)
      setOverview(data)
      if (data?.queryMode) setLiveMode(data.queryMode)
      if (data?.executive?.month && !month) {
        setSelectedMonth(data.executive.month)
      }
    } catch (e: any) {
      setError(e?.message || '经营看板加载失败')
    } finally {
      setOverviewLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    api.get<any>('/api/settings/query-mode')
      .then((d) => { if (d?.queryMode) setLiveMode(d.queryMode) })
      .catch(() => {})
    loadOverview()
  }, [loadOverview])

  useEffect(() => {
    if (!overviewLoading && !refreshing) {
      setElapsedSec(0)
      return
    }
    const start = Date.now()
    setElapsedSec(0)
    const timer = window.setInterval(() => {
      setElapsedSec((Date.now() - start) / 1000)
    }, 100)
    return () => window.clearInterval(timer)
  }, [overviewLoading, refreshing])

  async function switchToLocalMode() {
    if (switchingMode) return
    setSwitchingMode(true)
    try {
      await api.post('/api/settings/query-mode', { queryMode: 'local' })
      window.location.reload()
    } catch (e: any) {
      setError(e?.message || '切换本地数据模式失败')
      setSwitchingMode(false)
    }
  }

  async function onChangeMonth(m: string) {
    setSelectedMonth(m)
    await loadOverview(m, false)
  }

  const hour = new Date().getHours()
  const greet = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'

  const exec = overview?.executive
  const syncHealth = overview?.syncHealth
  const activeQueryMode = overview?.queryMode || liveMode || 'local'
  const isRealtime = activeQueryMode === 'realtime'
  const activeMonth = selectedMonth || exec?.month || ''
  const maxCustDebt = useMemo(
    () => Math.max(1, ...(exec?.topDebtCustomers || []).map((c) => c.totalDebt || 0)),
    [exec?.topDebtCustomers]
  )

  return (
    <>
      <div className="dash-head">
        <div className="dash-head-left">
          <h1>{greet}，{displayName}</h1>
          <div className="dash-status-strip">
            <span className={`status-dot ${isRealtime ? 'warn' : 'ok'}`} />
            <span>{isRealtime ? '实时聚合模式' : '本地同步库'}</span>
            {syncHealth?.lastSyncedAt && !isRealtime && (
              <>
                <span className="muted">·</span>
                <span>最近同步 {fmtSyncTime(syncHealth.lastSyncedAt)}</span>
              </>
            )}
            {exec?.contractCount != null && (
              <>
                <span className="muted">·</span>
                <span>在管合同行 {exec.contractCount} 条</span>
              </>
            )}
            {isAdmin && (syncHealth?.failed || 0) > 0 && (
              <Link to="/sync" className="dash-alert-pill">
                <Icon name="alert" size={12} />
                {syncHealth!.failed} 项同步失败
              </Link>
            )}
          </div>
        </div>

        <div className="dash-head-actions">
          {(exec?.availableMonths?.length || 0) > 0 && (
            <select
              className="select dash-month-select"
              aria-label="选择经营统计月份"
              value={activeMonth}
              onChange={(e) => onChangeMonth(e.target.value)}
            >
              {exec!.availableMonths.map((m) => (
                <option key={m} value={m}>{m} 月经营</option>
              ))}
            </select>
          )}
          <button
            type="button"
            className="btn sm"
            disabled={refreshing}
            onClick={() => loadOverview(selectedMonth || undefined, true)}
            title="强制重新计算最新经营指标"
          >
            <Icon name="refresh" size={13} className={refreshing ? 'spin' : ''} />
            {refreshing ? '刷新中…' : '刷新'}
          </button>
          <Link
            to={activeMonth ? `/reports/rpt_contract_scroll?month=${encodeURIComponent(activeMonth)}` : '/reports/rpt_contract_scroll'}
            className="btn sm primary"
          >
            <Icon name="report" size={13} />滚动明细表
          </Link>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {refreshing && exec && (
        <div className={`dash-load-banner compact${isRealtime ? ' realtime' : ''}`}>
          <div className="dash-load-top">
            <div className="dash-load-left">
              <span className="dash-load-spinner" aria-hidden />
              <div className="dash-load-title">
                {isRealtime ? '正在实时拉取最新 U9 数据重新核算…' : '正在重新计算最新经营指标…'}
                <span className="dash-load-timer mono">已等待 {elapsedSec.toFixed(1)}s</span>
              </div>
            </div>
          </div>
        </div>
      )}

      {overviewLoading && !exec ? (
        <DashboardLoadingView
          isRealtime={isRealtime}
          elapsedSec={elapsedSec}
          isAdmin={isAdmin}
          switchingMode={switchingMode}
          onSwitchLocal={switchToLocalMode}
        />
      ) : exec?.error ? (
        <EmptyState
          variant="data"
          title="经营看板暂不可用"
          desc={exec.error}
          action={isAdmin ? <Link className="btn primary sm" to="/sync">前往数据同步</Link> : undefined}
        />
      ) : exec && exec.kpis?.length > 0 ? (
        <>
          <section className="dash-kpi-grid" aria-label="核心经营指标">
            {exec.kpis.map((k) => {
              const f = fmtSmartKpi(k.value, k.kind)
              const hasDelta = k.deltaPct != null
              const isUp = (k.deltaPct || 0) > 0
              const deltaTone = !hasDelta || k.positiveIsGood == null || k.deltaPct === 0
                ? 'neutral'
                : (isUp === k.positiveIsGood ? 'good' : 'bad')
              return (
                <Link
                  key={k.key}
                  to={k.drillUrl}
                  className="dash-kpi-card"
                  title={`点击进入明细报表 · 精确值：${f.exact}`}
                >
                  <div className="dash-kpi-top">
                    <span className="dash-kpi-label">{k.label}</span>
                    {hasDelta ? (
                      <span className={`delta-pill ${deltaTone}`}>
                        {k.deltaPct! > 0 ? '+' : ''}{k.deltaPct!.toFixed(1)}%
                      </span>
                    ) : (
                      <Icon name="chevronRight" size={13} className="dash-kpi-arrow" />
                    )}
                  </div>
                  <div className="dash-kpi-val">
                    <b>{f.main}</b>
                    <span>{f.unit}</span>
                  </div>
                  <div className="dash-kpi-foot">
                    <span className="dash-kpi-sub" title={k.sub}>{k.sub}</span>
                  </div>
                </Link>
              )
            })}
          </section>

          <section className="dash-bento">
            <div className="card dash-panel">
              <div className="dash-panel-head">
                <div>
                  <h3>月度经营走势</h3>
                  <p className="sub">当前高亮 {activeMonth}，点击月份柱体联动全页数据</p>
                </div>
                <div className="seg" role="group" aria-label="图表指标切换">
                  <button
                    type="button"
                    className={`seg-item${chartMode === 'finance' ? ' active' : ''}`}
                    onClick={() => setChartMode('finance')}
                  >
                    资金回笼
                  </button>
                  <button
                    type="button"
                    className={`seg-item${chartMode === 'tonnage' ? ' active' : ''}`}
                    onClick={() => setChartMode('tonnage')}
                  >
                    产销吨位
                  </button>
                </div>
              </div>
              <MonthlyTrendChart
                series={exec.monthlySeries || []}
                activeMonth={activeMonth}
                mode={chartMode}
                onSelectMonth={onChangeMonth}
              />
            </div>

            <div className="card dash-panel">
              <div className="dash-panel-head">
                <div className="tabs">
                  <button
                    type="button"
                    className={focusTab === 'debt' ? 'active' : ''}
                    onClick={() => setFocusTab('debt')}
                  >
                    客户欠款 Top
                  </button>
                  <button
                    type="button"
                    className={focusTab === 'alerts' ? 'active' : ''}
                    onClick={() => setFocusTab('alerts')}
                  >
                    负库存预警
                    {(exec.stockAlertTotal || 0) > 0 && (
                      <span className="tab-count">{exec.stockAlertTotal}</span>
                    )}
                  </button>
                </div>
                <Link to={`/reports/rpt_contract_scroll?month=${encodeURIComponent(activeMonth)}`} className="btn sm ghost">
                  全部明细
                </Link>
              </div>

              {focusTab === 'debt' ? (
                (exec.topDebtCustomers?.length || 0) === 0 ? (
                  <EmptyState compact variant="data" title="当月无应收欠款客户" />
                ) : (
                  <div className="dash-rank-list">
                    {exec.topDebtCustomers.map((c, idx) => {
                      const pct = Math.max(4, Math.min(100, (c.totalDebt / maxCustDebt) * 100))
                      const drill = `/reports/rpt_contract_scroll?month=${encodeURIComponent(activeMonth)}&customer=${encodeURIComponent(c.customerName)}`
                      return (
                        <button
                          type="button"
                          key={c.customerName}
                          className="dash-rank-item"
                          onClick={() => navigate(drill)}
                          title={`点击查看「${c.customerName}」合同明细`}
                        >
                          <div className="dash-rank-top">
                            <span className="dash-rank-name">
                              <i className="rank-num">{idx + 1}</i>
                              <span>{c.customerName}</span>
                              {c.contractCount > 0 && <em className="rank-meta">{c.contractCount} 合同</em>}
                            </span>
                            <b className="dash-rank-val">
                              {c.totalDebt >= 10000
                                ? `${(c.totalDebt / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} 万`
                                : `${c.totalDebt.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} 元`}
                            </b>
                          </div>
                          <div className="dash-bar-track">
                            <div className="dash-bar-fill" style={{ width: `${pct}%` }} />
                          </div>
                        </button>
                      )
                    })}
                  </div>
                )
              ) : (exec.stockAlerts?.length || 0) === 0 ? (
                <EmptyState compact variant="data" title="暂无负库存异常" desc="当前月份所有合同黑白件结存均为非负" />
              ) : (
                <div className="dash-rank-list">
                  {exec.stockAlerts.map((a, idx) => {
                    const qs = new URLSearchParams({ month: activeMonth })
                    if (a.contractNo) qs.set('contractNo', a.contractNo)
                    else if (a.customerName) qs.set('customer', a.customerName)
                    return (
                      <button
                        type="button"
                        key={`${a.contractNo}-${a.customerName}-${idx}`}
                        className="dash-rank-item"
                        onClick={() => navigate(`/reports/rpt_contract_scroll?${qs.toString()}`)}
                      >
                        <div className="dash-rank-top">
                          <span className="dash-rank-name">
                            <span className="mono">{a.contractNo || '无合同号'}</span>
                            <em className="rank-meta">{a.customerName}</em>
                          </span>
                          <span className="row" style={{ gap: 6 }}>
                            {a.blackStock < -0.001 && (
                              <span className="badge err">黑 {a.blackStock}t</span>
                            )}
                            {a.whiteStock < -0.001 && (
                              <span className="badge err">白 {a.whiteStock}t</span>
                            )}
                          </span>
                        </div>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>
          </section>
        </>
      ) : (
        <EmptyState variant="data" title="暂无经营数据" desc="请先同步 U9 业务数据后刷新" />
      )}
    </>
  )
}
