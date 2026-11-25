import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, getUser } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { CardSkeleton } from '../components/Skeleton'

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

export default function Home() {
  const user = getUser()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'admin'
  const displayName = user?.displayName || user?.username || '朋友'

  const [overview, setOverview] = useState<OverviewData | null>(null)
  const [selectedMonth, setSelectedMonth] = useState<string>('')
  const [overviewLoading, setOverviewLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
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
    loadOverview()
  }, [loadOverview])

  async function onChangeMonth(m: string) {
    setSelectedMonth(m)
    await loadOverview(m, false)
  }

  const hour = new Date().getHours()
  const greet = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'

  const exec = overview?.executive
  const syncHealth = overview?.syncHealth
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
            <span className="status-dot ok" />
            <span>{overview?.queryMode === 'realtime' ? '实时聚合模式' : '本地同步库'}</span>
            {syncHealth?.lastSyncedAt && (
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

      {overviewLoading && !exec ? (
        <div className="dash-kpi-grid">
          <CardSkeleton count={5} />
        </div>
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
