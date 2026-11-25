import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import { TableSkeleton } from '../components/Skeleton'
import {
  SyncStatus,
  WEEKDAYS,
  fmtDuration,
  fmtTime,
  formatScheduleSummary,
  modeLabel,
  statusInfo,
  triggerInfo,
} from './syncUtils'

type ConfigDraft = {
  enabled: boolean
  scheduleType: 'manual' | 'interval' | 'daily' | 'weekly'
  scheduleTime: string
  scheduleWeekday: number
  scheduleIntervalMin: number
  pageSize: number
  maxPages: number
  notes: string
}

const SCHEDULE_MODES: { value: ConfigDraft['scheduleType']; title: string; desc: string }[] = [
  { value: 'manual', title: '仅手动触发', desc: '不参与自动调度，按需手工执行' },
  { value: 'interval', title: '按间隔循环', desc: '每隔 N 分钟自动拉取最新变更' },
  { value: 'daily', title: '每天定时', desc: '每日固定时刻自动同步一次' },
  { value: 'weekly', title: '每周定时', desc: '每周指定星期与时刻同步一次' },
]

const QUICK_TIMES = ['02:00', '06:00', '12:00', '18:00', '23:00']
const QUICK_INTERVALS = [15, 30, 60, 180, 360]

function toDraft(d: SyncStatus): ConfigDraft {
  const st = d.scheduleType
  const scheduleType: ConfigDraft['scheduleType'] =
    st === 'interval' || st === 'daily' || st === 'weekly' ? st : 'manual'
  return {
    enabled: Boolean(d.enabled),
    scheduleType,
    scheduleTime: d.scheduleTime || '18:00',
    scheduleWeekday: d.scheduleWeekday || 7,
    scheduleIntervalMin: d.scheduleIntervalMin || 60,
    pageSize: d.pageSize || 200,
    maxPages: d.maxPages || 500,
    notes: d.notes || '',
  }
}

export default function SyncConfigDetail() {
  const { datasetCode = '' } = useParams()
  const navigate = useNavigate()
  const aliveRef = useRef(true)

  const [allItems, setAllItems] = useState<SyncStatus[]>([])
  const [detail, setDetail] = useState<SyncStatus | null>(null)
  const [draft, setDraft] = useState<ConfigDraft | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState('')
  const [msg, setMsg] = useState('')

  async function loadAll(targetCode: string) {
    setLoading(true)
    setError('')
    try {
      const [cfg, list] = await Promise.all([
        api.get<SyncStatus>(`/api/sync/${encodeURIComponent(targetCode)}/config`),
        api.get<SyncStatus[]>('/api/sync/status'),
      ])
      if (aliveRef.current) {
        setDetail(cfg)
        setDraft(toDraft(cfg))
        setAllItems(list || [])
      }
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
    } finally {
      if (aliveRef.current) setLoading(false)
    }
  }

  async function refreshStatusOnly(targetCode: string): Promise<SyncStatus | null> {
    try {
      const cfg = await api.get<SyncStatus>(`/api/sync/${encodeURIComponent(targetCode)}/config`)
      if (aliveRef.current) setDetail(cfg)
      return cfg
    } catch {
      return null
    }
  }

  useEffect(() => {
    aliveRef.current = true
    if (datasetCode) loadAll(datasetCode)
    return () => { aliveRef.current = false }
  }, [datasetCode])

  const isDirty = useMemo(() => {
    if (!detail || !draft) return false
    const orig = toDraft(detail)
    return (
      orig.enabled !== draft.enabled ||
      orig.scheduleType !== draft.scheduleType ||
      orig.scheduleTime !== draft.scheduleTime ||
      orig.scheduleWeekday !== draft.scheduleWeekday ||
      orig.scheduleIntervalMin !== draft.scheduleIntervalMin ||
      orig.pageSize !== draft.pageSize ||
      orig.maxPages !== draft.maxPages ||
      orig.notes !== draft.notes
    )
  }, [detail, draft])

  async function saveConfig() {
    if (!draft || !datasetCode) return
    setSaving(true)
    setError('')
    setMsg('')
    try {
      const updated = await api.post<SyncStatus>(
        `/api/sync/${encodeURIComponent(datasetCode)}/config`,
        {
          enabled: draft.enabled,
          scheduleType: draft.scheduleType,
          scheduleTime: draft.scheduleType === 'daily' || draft.scheduleType === 'weekly' ? draft.scheduleTime : null,
          scheduleWeekday: draft.scheduleType === 'weekly' ? draft.scheduleWeekday : null,
          scheduleIntervalMin: draft.scheduleType === 'interval' ? draft.scheduleIntervalMin : null,
          pageSize: draft.pageSize,
          maxPages: draft.maxPages,
          notes: draft.notes,
        },
      )
      if (aliveRef.current) {
        setDetail(updated)
        setDraft(toDraft(updated))
        setMsg('配置已保存并立即生效')
      }
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
    } finally {
      if (aliveRef.current) setSaving(false)
    }
  }

  async function runNow(strategy: 'incremental' | 'full') {
    if (!datasetCode) return
    if (strategy === 'full' && !confirm(`确定对「${detail?.name || datasetCode}」执行全量同步？将清库重拉该表数据。`)) {
      return
    }
    setRunning(true)
    setError('')
    setMsg('')
    try {
      await api.post(`/api/sync/${encodeURIComponent(datasetCode)}/run`, { strategy })
      setMsg(`已触发${strategy === 'full' ? '全量' : '增量'}同步，正在等待执行结果…`)
      for (let i = 0; i < 40; i++) {
        await new Promise((r) => setTimeout(r, 1500))
        if (!aliveRef.current) return
        const fresh = await refreshStatusOnly(datasetCode)
        if (fresh && fresh.lastJobStatus && fresh.lastJobStatus !== 'running' && fresh.lastJobStatus !== 'queued') {
          if (aliveRef.current) {
            const st = statusInfo(fresh.lastJobStatus).label
            setMsg(`同步${st}：本次拉取 ${fresh.lastJobRows ?? 0} 行，耗时 ${fmtDuration(fresh.lastJobDurationMs)}`)
          }
          break
        }
      }
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
    } finally {
      if (aliveRef.current) setRunning(false)
    }
  }

  async function stopNow() {
    if (!datasetCode) return
    try {
      await api.post(`/api/sync/${encodeURIComponent(datasetCode)}/stop`)
      setMsg('已发送停止请求')
      setTimeout(() => refreshStatusOnly(datasetCode), 1000)
    } catch (e: any) {
      setError(e.message)
    }
  }

  if (loading && !detail) {
    return (
      <div className="card" style={{ padding: 24 }}>
        <TableSkeleton rows={5} cols={3} />
      </div>
    )
  }

  if (!detail || !draft) {
    return (
      <div className="card">
        <div className="error-banner">{error || `未找到数据集 ${datasetCode} 的同步配置`}</div>
        <Link className="btn sm" to="/sync">返回数据同步列表</Link>
      </div>
    )
  }

  const st = statusInfo(detail.lastJobStatus)
  const isJobActive = detail.lastJobStatus === 'running' || detail.lastJobStatus === 'queued'
  const schedSummary = formatScheduleSummary(draft)
  const schedStrategyText = detail.scheduledStrategy === 'full'
    ? '全量替换（无增量过滤条件，定时自动清理上游删单残留）'
    : '增量更新（基于 incremental_filter 拉取变更并合并写入）'

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <Link className="btn sm ghost" to="/sync">
            <Icon name="arrowLeft" size={14} />返回列表
          </Link>
          <div style={{ minWidth: 0 }}>
            <h1 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span>{detail.name || detail.datasetCode}</span>
              <span className="badge muted">{modeLabel(detail)}</span>
            </h1>
            <p className="one-line mono">
              {detail.datasetCode}
              {detail.dataSourceName ? ` · ${detail.dataSourceName}` : ''}
              {detail.endpointCode ? ` · ${detail.endpointCode}` : ''}
            </p>
          </div>
        </div>
        <div className="row">
          {allItems.length > 1 && (
            <select
              className="select sync-jump-select"
              value={detail.datasetCode}
              onChange={(e) => {
                if (isDirty && !confirm('当前有未保存的修改，确认切换数据集？')) return
                navigate(`/sync/${encodeURIComponent(e.target.value)}`)
              }}
              aria-label="切换其他数据集"
            >
              {allItems.map((it) => (
                <option key={it.datasetCode} value={it.datasetCode}>
                  {it.name || it.datasetCode} ({it.datasetCode})
                </option>
              ))}
            </select>
          )}
          <Link className="btn sm" to={`/sync-logs?dataset=${encodeURIComponent(detail.datasetCode)}`}>
            <Icon name="clock" size={13} />同步记录
          </Link>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {msg && (
        <div className="sync-notice">
          <Icon name="check" size={14} />
          <span>{msg}</span>
        </div>
      )}

      <div className="sync-cfg-grid">
        {/* 左侧：核心配置表单 */}
        <div className="sync-cfg-main">
          {/* 1. 启停与调度计划 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>启用状态与定时调度</h3>
                <p className="muted">设置该数据集是否参与同步以及自动调度频次，点击底部「保存配置」后统一生效</p>
              </div>
              <label className="sync-toggle-inline">
                <span>{draft.enabled ? '已启用' : '已停用'}</span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={draft.enabled}
                  className={`sync-switch${draft.enabled ? ' on' : ''}`}
                  onClick={() => setDraft({ ...draft, enabled: !draft.enabled })}
                >
                  <span className="sync-switch-thumb" />
                </button>
              </label>
            </div>

            <div className="sync-mode-cards" role="radiogroup" aria-label="调度模式">
              {SCHEDULE_MODES.map((m) => (
                <button
                  type="button"
                  role="radio"
                  aria-checked={draft.scheduleType === m.value}
                  key={m.value}
                  className={`sync-mode-card${draft.scheduleType === m.value ? ' active' : ''}`}
                  onClick={() => setDraft({ ...draft, scheduleType: m.value })}
                >
                  <div className="sync-mode-card-title">
                    <span className="sync-radio-dot" />
                    <b>{m.title}</b>
                  </div>
                  <span className="sync-mode-card-desc">{m.desc}</span>
                </button>
              ))}
            </div>

            {draft.scheduleType === 'interval' && (
              <div className="sync-sched-params">
                <div className="field">
                  <label>执行间隔（分钟，5 – 10080）</label>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <input
                      className="input"
                      type="number"
                      min={5}
                      max={10080}
                      style={{ width: 120 }}
                      value={draft.scheduleIntervalMin}
                      onChange={(e) => setDraft({ ...draft, scheduleIntervalMin: Math.max(5, Number(e.target.value) || 60) })}
                    />
                    <span className="muted">分钟</span>
                    <div className="sync-preset-row">
                      {QUICK_INTERVALS.map((m) => (
                        <button
                          type="button"
                          key={m}
                          className={`sync-preset-btn${draft.scheduleIntervalMin === m ? ' active' : ''}`}
                          onClick={() => setDraft({ ...draft, scheduleIntervalMin: m })}
                        >
                          {m >= 60 ? `${m / 60}小时` : `${m}分`}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {(draft.scheduleType === 'daily' || draft.scheduleType === 'weekly') && (
              <div className="sync-sched-params">
                {draft.scheduleType === 'weekly' && (
                  <div className="field" style={{ marginBottom: 12 }}>
                    <label>执行星期</label>
                    <div className="sync-weekday-group">
                      {WEEKDAYS.map((label, idx) => {
                        const day = idx + 1
                        return (
                          <button
                            type="button"
                            key={day}
                            className={`sync-weekday-btn${draft.scheduleWeekday === day ? ' active' : ''}`}
                            onClick={() => setDraft({ ...draft, scheduleWeekday: day })}
                          >
                            {label}
                          </button>
                        )
                      })}
                    </div>
                  </div>
                )}
                <div className="field">
                  <label>执行时间（24 小时制）</label>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <input
                      className="input"
                      type="time"
                      style={{ width: 132 }}
                      value={draft.scheduleTime}
                      onChange={(e) => setDraft({ ...draft, scheduleTime: e.target.value || '18:00' })}
                    />
                    <div className="sync-preset-row">
                      {QUICK_TIMES.map((t) => (
                        <button
                          type="button"
                          key={t}
                          className={`sync-preset-btn${draft.scheduleTime === t ? ' active' : ''}`}
                          onClick={() => setDraft({ ...draft, scheduleTime: t })}
                        >
                          {t}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              </div>
            )}

            <div className="sync-preview-bar">
              <Icon name="clock" size={14} />
              <div>
                <b>调度预览：</b>
                {!draft.enabled ? (
                  <span>当前配置为停用状态，保存后将不会自动触发定时同步。</span>
                ) : draft.scheduleType === 'manual' ? (
                  <span>已启用同步，但未开启定时任务，仅在手工触发或一键同步时执行。</span>
                ) : (
                  <span>
                    保存后将按 <strong>{schedSummary}</strong> 自动执行 · 定时策略：{schedStrategyText}
                  </span>
                )}
              </div>
            </div>
          </section>

          {/* 2. 分页参数与备注 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>拉取参数与说明</h3>
                <p className="muted">控制单次分页请求规模与保护上限，避免单次拉取过大拖慢上游接口</p>
              </div>
            </div>

            <div className="form-grid">
              <div className="field">
                <label>每页条数（pageSize）</label>
                <input
                  className="input"
                  type="number"
                  min={10}
                  max={5000}
                  value={draft.pageSize}
                  onChange={(e) => setDraft({ ...draft, pageSize: Number(e.target.value) || 200 })}
                />
                <span className="hint">建议 100 – 500；U9 查询接口过大可能超时</span>
              </div>
              <div className="field">
                <label>最大翻页数（maxPages）</label>
                <input
                  className="input"
                  type="number"
                  min={1}
                  max={5000}
                  value={draft.maxPages}
                  onChange={(e) => setDraft({ ...draft, maxPages: Number(e.target.value) || 500 })}
                />
                <span className="hint">防死循环保护上限，最大可拉约 {(draft.pageSize * draft.maxPages).toLocaleString('zh-CN')} 行</span>
              </div>
            </div>

            <div className="field" style={{ marginTop: 12 }}>
              <label>配置备注</label>
              <textarea
                className="input"
                rows={3}
                placeholder="记录该表同步口径、依赖顺序或排障注意事项…"
                value={draft.notes}
                onChange={(e) => setDraft({ ...draft, notes: e.target.value })}
              />
            </div>
          </section>

          {/* 3. 底层接入定义（只读） */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>底层接入定义（只读）</h3>
                <p className="muted">数据源、接口与增量/取键条件由系统集成维护，此处仅供核对排障</p>
              </div>
            </div>

            <div className="sync-meta-grid">
              <div><span>所属数据源</span><b>{detail.dataSourceName || '—'}</b></div>
              <div><span>绑定接口</span><b className="mono">{detail.endpointCode || '—'}</b></div>
              <div>
                <span>接口路径</span>
                <b className="mono">{detail.endpointPath ? `${detail.endpointMethod || 'GET'} ${detail.endpointPath}` : '—'}</b>
              </div>
              <div><span>同步模式</span><b>{modeLabel(detail)}（{detail.mode}）</b></div>
              {detail.keysEndpoint && <div><span>取键接口</span><b className="mono">{detail.keysEndpoint}</b></div>}
              {detail.parentDataset && <div><span>父数据集</span><b className="mono">{detail.parentDataset}</b></div>}
            </div>

            {detail.incrementalFilter && (
              <div className="sync-code-block">
                <div className="sync-code-label">增量过滤规则（incrementalFilter）</div>
                <pre>{detail.incrementalFilter}</pre>
              </div>
            )}
            {detail.keysSql && (
              <div className="sync-code-block">
                <div className="sync-code-label">全量取键 SQL（keysSql）</div>
                <pre>{detail.keysSql}</pre>
              </div>
            )}
          </section>

          {/* 底部保存栏 */}
          <div className={`sync-save-bar${isDirty ? ' dirty' : ''}`}>
            <div className="sync-save-state">
              {isDirty ? (
                <>
                  <span className="sync-dirty-dot" />
                  <span>有未保存的配置更改</span>
                </>
              ) : (
                <span className="muted">当前配置与服务端一致</span>
              )}
            </div>
            <div className="row" style={{ gap: 8 }}>
              <button
                type="button"
                className="btn sm"
                disabled={!isDirty || saving}
                onClick={() => setDraft(toDraft(detail))}
              >
                撤销修改
              </button>
              <button
                type="button"
                className="btn sm primary"
                disabled={!isDirty || saving}
                onClick={saveConfig}
              >
                {saving ? '保存中…' : '保存配置'}
              </button>
            </div>
          </div>
        </div>

        {/* 右侧：实时运行状态与快捷触发 */}
        <aside className="sync-cfg-aside">
          <div className="card">
            <div className="rel-insp-head">
              <span className={`badge ${st.tone}`}>{st.label}</span>
              <button type="button" className="btn sm ghost" onClick={() => refreshStatusOnly(detail.datasetCode)}>
                <Icon name="refresh" size={12} />刷新
              </button>
            </div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>运行状态</h3>
            <div className="rel-insp-kv">
              <div>
                <span>本地已同步行数</span>
                <b className="mono" style={{ fontSize: 18, color: 'var(--accent)' }}>
                  {Number(detail.recordCount || 0).toLocaleString('zh-CN')} 行
                </b>
              </div>
              <div><span>最近同步完成</span><b className="mono">{fmtTime(detail.lastSyncedAt)}</b></div>
              <div><span>下次计划执行</span><b className="mono">{detail.enabled ? fmtTime(detail.nextScheduleRun) : '已停用'}</b></div>
              <div><span>最近任务拉取</span><b>{detail.lastJobRows != null ? `${detail.lastJobRows} 行 · ${fmtDuration(detail.lastJobDurationMs)}` : '—'}</b></div>
              {detail.lastJobTrigger && (
                <div><span>最近触发方式</span><b>{triggerInfo(detail.lastJobTrigger).label}</b></div>
              )}
            </div>
            {detail.lastJobError && (
              <div className="sync-aside-err">
                <b>最近错误信息</b>
                <p>{detail.lastJobError}</p>
              </div>
            )}
          </div>

          <div className="card">
            <h3 style={{ margin: '0 0 6px', fontSize: 15 }}>即时操作</h3>
            <p className="muted" style={{ margin: '0 0 12px', fontSize: 12, lineHeight: 1.55 }}>
              增量同步仅拉取最新变更并合并写入；若上游修改或删除了历史单据，请使用全量同步。
            </p>
            <div className="stack" style={{ gap: 8 }}>
              {isJobActive ? (
                <button type="button" className="btn danger" style={{ width: '100%' }} onClick={stopNow}>
                  停止当前同步任务
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    className="btn primary"
                    style={{ width: '100%' }}
                    disabled={running || !detail.enabled}
                    onClick={() => runNow('incremental')}
                  >
                    <Icon name="bolt" size={14} />{running ? '同步执行中…' : '立即增量同步'}
                  </button>
                  <button
                    type="button"
                    className="btn"
                    style={{ width: '100%' }}
                    disabled={running || !detail.enabled}
                    onClick={() => runNow('full')}
                  >
                    立即全量同步（清库重拉）
                  </button>
                </>
              )}
              <Link
                className="btn ghost"
                style={{ width: '100%' }}
                to={`/sync-logs?dataset=${encodeURIComponent(detail.datasetCode)}`}
              >
                <Icon name="clock" size={14} />查看该表全部同步记录
              </Link>
            </div>
          </div>
        </aside>
      </div>
    </>
  )
}
