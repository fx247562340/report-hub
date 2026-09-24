import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, getUser } from '../api/client'
import Icon, { IconName } from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { CardSkeleton } from '../components/Skeleton'

type Report = {
  code: string
  name: string
  description?: string
  rootDataset: string
  fieldsJson?: string
  filtersJson?: string
}

type WidgetCfg = {
  id?: string
  reportCode: string
  title: string
  metric: 'count' | 'sum' | 'avg' | 'min' | 'max' | string
  field?: string
  filtersJson?: string
  unit?: string
  timeRange?: 'today' | 'week' | 'month' | string
  dateFilterKey?: string
  sort?: number
  enabled?: boolean
}

type WidgetValue = WidgetCfg & {
  value: number | null
  reportName?: string
  ok?: boolean
  error?: string
  dateStart?: string
  dateEnd?: string
}

const METRIC_LABEL: Record<string, string> = {
  count: '总条数',
  count_distinct: '去重计数',
  sum: '合计',
  avg: '平均',
  min: '最小',
  max: '最大',
}

const TIME_LABEL: Record<string, string> = {
  today: '当天',
  week: '本周',
  month: '当月',
}

const METRIC_ICON: Record<string, IconName> = {
  count: 'report',
  count_distinct: 'users',
  sum: 'bolt',
  avg: 'dataset',
  min: 'chevronDown',
  max: 'chevronUp',
}

function countJson(json?: string) {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr.length : 0
  } catch {
    return 0
  }
}

function parseFilterLabels(json?: string): string[] {
  try {
    const arr = JSON.parse(json || '[]')
    if (!Array.isArray(arr)) return []
    return arr.map((x: any) => x.label || x.key).filter(Boolean).slice(0, 3)
  } catch {
    return []
  }
}

function fmtValue(v: number | null | undefined, metric?: string) {
  if (v == null) return '—'
  if (metric === 'count') return v.toLocaleString()
  return Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2)
}

const emptyWidget = (reports: Report[]): WidgetCfg => ({
  reportCode: reports[0]?.code || '',
  title: '',
  metric: 'count',
  field: '',
  filtersJson: '{}',
  unit: '',
  enabled: true,
})

export default function Home() {
  const user = getUser()
  const isAdmin = user?.role === 'admin'
  const displayName = user?.displayName || user?.username || '朋友'
  const [reports, setReports] = useState<Report[]>([])
  const [stat, setStat] = useState({ datasources: 0, endpoints: 0, datasets: 0, relations: 0 })
  const [widgets, setWidgets] = useState<WidgetValue[]>([])
  const [dashLoading, setDashLoading] = useState(true)
  const [loading, setLoading] = useState(true)
  const [cfgOpen, setCfgOpen] = useState(false)
  const [draft, setDraft] = useState<WidgetCfg[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    const loadReports = async () => {
      setLoading(true)
      try {
        const list = await api.get<Report[]>('/api/reports')
        if (alive) setReports(list)
        if (isAdmin) {
          const [ds, ep, dset] = await Promise.all([
            api.get<any[]>('/api/admin/datasources'),
            api.get<any[]>('/api/admin/endpoints'),
            api.get<any[]>('/api/admin/datasets'),
          ])
          let relCount = 0
          try {
            const relLists = await Promise.all(
              (list || []).map((r) =>
                api.get<any[]>(`/api/admin/reports/${r.code}/relations`).catch(() => [])
              )
            )
            relCount = relLists.reduce((n, arr) => n + (arr?.length || 0), 0)
          } catch { relCount = 0 }
          if (alive) {
            setStat({
              datasources: ds.length,
              endpoints: ep.length,
              datasets: dset.length,
              relations: relCount,
            })
          }
        }
      } catch {
        if (alive) setReports([])
      } finally {
        if (alive) setLoading(false)
      }
    }
    const loadDash = async () => {
      setDashLoading(true)
      try {
        for (let i = 0; i < 36; i++) {
          const dash = await api.get<WidgetValue[]>('/api/dashboard')
          if (!alive) return
          setWidgets(dash || [])
          if (!(dash || []).some((w: any) => w.value == null && w.ok !== false)) break
          await new Promise((r) => setTimeout(r, i < 5 ? 1000 : 2500))
        }
      } catch {
        if (alive) setWidgets([])
      } finally {
        if (alive) setDashLoading(false)
      }
    }
    loadReports()
    loadDash()
    return () => { alive = false }
  }, [isAdmin])

  const hour = new Date().getHours()
  const greet = hour < 6 ? '夜深了' : hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'

  async function openCfg() {
    setError('')
    let list: WidgetCfg[] = []
    try {
      list = await api.get<WidgetCfg[]>('/api/admin/dashboard/widgets')
    } catch {
      list = []
    }
    // 固定 4 格
    const slots: WidgetCfg[] = [0, 1, 2, 3].map((i) => {
      const w = list[i]
      return {
        id: w?.id,
        reportCode: w?.reportCode || reports[0]?.code || '',
        title: w?.title || '',
        metric: w?.metric || 'count',
        field: w?.field || '',
        filtersJson: w?.filtersJson || '{}',
        unit: w?.unit || '',
        timeRange: w?.timeRange || 'month',
        dateFilterKey: w?.dateFilterKey || '',
        sort: i,
        enabled: true,
      }
    })
    setDraft(slots)
    setCfgOpen(true)
  }

  async function saveWidgets() {
    try {
      const payload = [0, 1, 2, 3].map((i) => {
        const w = draft[i] || emptyWidget(reports)
        return {
          ...w,
          id: w.id,
          title: w.title || `指标 ${i + 1}`,
          reportCode: w.reportCode || reports[0]?.code || '',
          metric: w.metric || 'count',
          filtersJson: w.filtersJson || '{}',
          timeRange: w.timeRange || 'month',
          dateFilterKey: w.dateFilterKey || '',
          sort: i,
          enabled: true,
        }
      })
      await api.post('/api/admin/dashboard/widgets', payload)
      const dash = await api.get<WidgetValue[]>('/api/dashboard')
      setWidgets(dash || [])
      setCfgOpen(false)
    } catch (e: any) {
      setError(e.message)
    }
  }

  return (
    <>
      <div className="page-head home-hero">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="home" size={22} /></div>
          <div>
            <h1>{greet}，{displayName}</h1>
            <p>关键指标与可用报表，数据实时来自上游系统</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn" to="/about"><Icon name="help" size={14} />使用说明</Link>
          {isAdmin && (
            <>
              <button className="btn" onClick={openCfg}><Icon name="config" size={14} />配置看板</button>
              <Link className="btn primary" to="/reports"><Icon name="report" size={15} />管理报表</Link>
            </>
          )}
        </div>
      </div>

      {/* 数据看板 · 固定 4 格 */}
      <div className="section-head">
        <div>
          <h2>数据看板</h2>
          <p className="muted">固定 4 个指标，管理员可改统计口径</p>
        </div>
        {isAdmin && (
          <button className="btn sm" onClick={openCfg}><Icon name="config" size={12} />配置指标</button>
        )}
      </div>
      {(() => {
        const slots = [0, 1, 2, 3].map((i) => widgets[i] || null)
        return (
          <div className="metrics metrics-4">
            {slots.map((w, i) =>
              w ? (
                <div className="card metric" key={w.id || i}>
                  <div className="label">
                    <span>{w.title || `指标 ${i + 1}`}</span>
                    <span className="badge muted">{TIME_LABEL[w.timeRange || 'month'] || w.timeRange}</span>
                  </div>
                  <div className="value">
                    {w.value != null
                      ? fmtValue(w.value, w.metric)
                      : (w.ok === false ? '—' : '计算中…')}
                    {w.value != null && w.unit ? <span style={{ fontSize: 14, marginLeft: 4 }}>{w.unit}</span> : null}
                  </div>
                  <div className="hint">
                    {w.dateStart ? `${w.dateStart} ~ ${w.dateEnd}` : ''}
                    {w.dateStart ? ' · ' : ''}
                    {METRIC_LABEL[w.metric] || w.metric}
                    {w.field ? ` · ${w.field}` : ''}
                  </div>
                </div>
              ) : (
                <div className="card metric" key={i}>
                  <div className="label"><span>指标 {i + 1}</span><Icon name="bolt" size={15} /></div>
                  <div className="value">—</div>
                  <div className="hint">未配置</div>
                </div>
              )
            )}
          </div>
        )
      })()}

      {/* 配置能力（仅管理员） */}
      {isAdmin && (
        <div className="metrics" style={{ gridTemplateColumns: 'repeat(4, 1fr)', marginTop: 4 }}>
          {loading ? (
            <CardSkeleton count={4} />
          ) : ([
            { to: '/datasources', label: '数据源', icon: 'datasource' as const, value: stat.datasources },
            { to: '/endpoints', label: '接口', icon: 'api' as const, value: stat.endpoints },
            { to: '/datasets', label: '数据集', icon: 'dataset' as const, value: stat.datasets },
            { to: '/relations', label: '关联', icon: 'relation' as const, value: stat.relations },
          ]).map((c) => (
            <Link key={c.to} to={c.to} className="card metric clickable">
              <div className="label"><span>{c.label}</span><Icon name={c.icon} size={15} /></div>
              <div className="value">{c.value}</div>
              <div className="hint">点击进入配置</div>
            </Link>
          ))}
        </div>
      )}

      <div className="section-head" style={{ marginTop: 8 }}>
        <div>
          <h2>可用报表</h2>
          <p className="muted">点卡片进入查询</p>
        </div>
        <span className="badge ok">{reports.length} 张</span>
      </div>

      {loading ? (
        <div className="report-grid">
          {[0, 1, 2].map((i) => <div className="report-card skel-card" key={i} />)}
        </div>
      ) : reports.length === 0 ? (
        <EmptyState
          variant="report"
          title="暂无可查询的报表"
          desc={isAdmin ? '在「报表」里配置后，这里会出现卡片' : '请联系管理员发布报表'}
          action={isAdmin ? <Link className="btn primary sm" to="/reports">去配置报表</Link> : undefined}
        />
      ) : (
        <div className="report-grid">
          {reports.map((r, idx) => {
            const fieldCount = countJson(r.fieldsJson)
            const filterLabels = parseFilterLabels(r.filtersJson)
            return (
              <Link
                to={`/reports/${r.code}`}
                key={r.code}
                className="report-card"
                style={{ animationDelay: `${idx * 0.05}s` }}
              >
                <div className="report-card-top">
                  <div className="report-card-icon"><Icon name="report" size={20} /></div>
                  <div className="report-card-arrow"><Icon name="arrowLeft" size={14} className="flip-x" /></div>
                </div>
                <h3>{r.name}</h3>
                <p className="report-card-desc">{r.description || `主数据集 ${r.rootDataset}`}</p>
                <div className="report-card-meta">
                  <span><Icon name="dataset" size={12} />{fieldCount || '—'} 列</span>
                  <span><Icon name="search" size={12} />{filterLabels.length || '无'} 筛选</span>
                </div>
                {filterLabels.length > 0 && (
                  <div className="report-card-tags">
                    {filterLabels.map((t) => <span key={t} className="badge muted">{t}</span>)}
                  </div>
                )}
                <div className="report-card-cta">
                  <span className="btn primary sm">进入查询</span>
                </div>
              </Link>
            )
          })}
        </div>
      )}

      {cfgOpen && (
        <div className="modal-backdrop" onClick={() => setCfgOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>配置数据看板</h3>
                <p className="sub">每张卡片 = 一条指标，可挂默认筛选</p>
              </div>
              <button className="btn sm ghost" onClick={() => setCfgOpen(false)}>关闭</button>
            </div>
            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}
              <div className="sec-title">
                <b>指标卡片</b>
                <span>固定 4 个，只改内容，不可增减</span>
              </div>
              {[0, 1, 2, 3].map((idx) => {
                const w = draft[idx] || emptyWidget(reports)
                return (
                  <div key={idx} className="form-section">
                    <div className="sec-title" style={{ marginBottom: 10 }}>
                      <b>指标 {idx + 1}</b>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>标题</label>
                        <input
                          className="input"
                          placeholder="如 挂次总条数"
                          value={w.title}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, title: e.target.value } : x)))}
                        />
                      </div>
                      <div className="field">
                        <label>报表</label>
                        <select
                          className="select"
                          value={w.reportCode}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, reportCode: e.target.value } : x)))}
                        >
                          {reports.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
                        </select>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>统计方式</label>
                        <select
                          className="select"
                          value={w.metric}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, metric: e.target.value } : x)))}
                        >
                          {Object.entries(METRIC_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                        </select>
                      </div>
                      <div className="field">
                        <label>字段 {w.metric === 'count' ? '（总条数可不填）' : '（去重/合计用列标识）'}</label>
                        <input
                          className="input mono"
                          placeholder={
                            w.metric === 'count_distinct'
                              ? '如 customer_name / contract_no / so_no'
                              : '如 order_weight'
                          }
                          value={w.field || ''}
                          disabled={w.metric === 'count'}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, field: e.target.value } : x)))}
                        />
                        {w.metric === 'count_distinct' && (
                          <span className="hint">按字段去重后计数：客户数、合同数、销售订单数…</span>
                        )}
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>统计时间</label>
                        <select
                          className="select"
                          value={w.timeRange || 'month'}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, timeRange: e.target.value } : x)))}
                        >
                          <option value="today">当天</option>
                          <option value="week">本周</option>
                          <option value="month">当月</option>
                        </select>
                        <span className="hint">最长一个月，减小查询量</span>
                      </div>
                      <div className="field">
                        <label>时间绑定筛选名</label>
                        <input
                          className="input mono"
                          placeholder="endImmersionTime（可空，自动识别）"
                          value={w.dateFilterKey || ''}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, dateFilterKey: e.target.value } : x)))}
                        />
                        <span className="hint">日期范围下推到接口的参数名</span>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>单位</label>
                        <input
                          className="input"
                          placeholder="如 行 / kg"
                          value={w.unit || ''}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, unit: e.target.value } : x)))}
                        />
                      </div>
                      <div className="field">
                        <label>其他筛选 JSON</label>
                        <input
                          className="input mono"
                          placeholder='{"work_status":"12"}'
                          value={w.filtersJson || '{}'}
                          onChange={(e) => setDraft(draft.map((x, i) => (i === idx ? { ...x, filtersJson: e.target.value } : x)))}
                        />
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setCfgOpen(false)}>取消</button>
              <button className="btn primary" onClick={saveWidgets}>保存看板</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
