import { useEffect, useState } from 'react'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'

type SyncStatus = {
  datasetCode: string
  name?: string
  endpointCode?: string
  enabled: boolean
  mode: string
  pageSize: number
  maxPages: number
  recordCount: number
  lastSyncedAt?: string
  scheduleType?: string
  scheduleTime?: string
  scheduleWeekday?: number
  scheduleIntervalMin?: number
  nextScheduleRun?: string
  lastScheduleRun?: string
  lastJobStatus?: string
  lastJobRows?: number
  lastJobError?: string
  lastJobDurationMs?: number
}

type SyncJob = {
  id: number
  datasetCode: string
  status: string
  startedAt: string
  finishedAt?: string
  rowCount: number
  pageCount: number
  durationMs?: number
  error?: string
  trigger: string
}

function fmtTime(s?: string) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}

export default function Sync() {
  const [list, setList] = useState<SyncStatus[]>([])
  const [jobs, setJobs] = useState<SyncJob[]>([])
  const [mode, setMode] = useState('local')
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [msg, setMsg] = useState('')

  async function load() {
    setLoading(true)
    setError('')
    try {
      const [s, j, m] = await Promise.all([
        api.get<SyncStatus[]>('/api/sync/status'),
        api.get<SyncJob[]>('/api/sync/jobs'),
        api.get<any>('/api/settings/query-mode'),
      ])
      setList(s || [])
      setJobs(j || [])
      setMode(m?.queryMode || 'local')
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function runAll(strategy: 'incremental' | 'full') {
    setBusy('ALL')
    setError('')
    setMsg('')
    try {
      await api.post('/api/sync/run-all', { strategy })
      setMsg(strategy === 'full' ? '已开始全量同步（按顺序执行），请稍候…' : '已开始增量同步（按顺序执行），请稍候…')
      for (let i = 0; i < 60; i++) {
        await new Promise((r) => setTimeout(r, 3000))
        await load()
        const running = list.some((x) => x.lastJobStatus === 'running')
        if (!running && i > 2) {
          setMsg(strategy === 'full' ? '全量同步已完成（各表请看最近任务状态）' : '增量同步已完成（各表请看最近任务状态）')
          break
        }
      }
    } catch (e: any) {
      setError(e.message)
    } finally {
      setBusy('')
    }
  }

  async function runSync(code: string, strategy: 'incremental' | 'full') {
    setBusy(code)
    setError('')
    setMsg('')
    try {
      await api.post(`/api/sync/${code}/run`, { strategy })
      setMsg(`已触发${strategy === 'full' ? '全量' : '增量'}同步 ${code}，完成后自动刷新`)
      // 轮询几次
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 1500))
        await load()
        const cur = list.find((x) => x.datasetCode === code) || (await api.get<SyncStatus[]>('/api/sync/status')).find((x: any) => x.datasetCode === code)
        if (cur && cur.lastJobStatus && cur.lastJobStatus !== 'running' && cur.lastJobStatus !== 'queued') {
          setMsg(`${code} 同步${cur.lastJobStatus === 'success' ? '完成' : '失败'}：${cur.lastJobRows ?? 0} 行`)
          break
        }
      }
    } catch (e: any) {
      setError(e.message)
    } finally {
      setBusy('')
    }
  }

  async function stopSync(code: string) {
    try {
      await api.post(`/api/sync/${code}/stop`)
      setMsg(`已请求停止 ${code}`)
      setTimeout(load, 1000)
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function saveSchedule(code: string, patch: Record<string, any>) {
    try {
      await api.post(`/api/sync/${code}/schedule`, patch)
      setMsg(`已更新 ${code} 的同步计划`)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function toggleEnabled(code: string, enabled: boolean) {
    try {
      await api.post(`/api/sync/${code}/enabled`, { enabled })
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="dataset" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>数据同步</h1>
            <p className="one-line">把上游原始数据同步到本地 · 字段映射仍在查询时应用</p>
          </div>
        </div>
        <div className="row">
          <span className="stat-chip">
            <i>查询模式</i>
            <b>{mode === 'local' ? '本地数据' : '实时聚合'}</b>
          </span>
          <button className="btn sm primary" onClick={() => runAll('incremental')} disabled={busy === 'ALL'}>
            <Icon name="bolt" size={13} />{busy === 'ALL' ? '同步中…' : '一键增量同步'}
          </button>
          <button className="btn sm" title="清库重取全部数据，上游改了历史数据时用" onClick={() => runAll('full')} disabled={busy === 'ALL'}>
            一键全量同步
          </button>
          <button className="btn sm" onClick={load} disabled={loading}>
            <Icon name="play" size={13} />{loading ? '刷新中…' : '刷新'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {msg && <div className="muted" style={{ marginBottom: 8 }}>{msg}</div>}

      <div className="card">
        <h3>同步对象</h3>
        <div className="table-wrap">
          {list.length === 0 ? (
            <EmptyState variant="config" title="暂无同步配置" desc="可在数据库 sync_configs 中添加数据集" />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>数据集</th>
                  <th>方式</th>
                  <th>接口</th>
                  <th>本地行数</th>
                  <th>最近同步</th>
                  <th>同步计划</th>
                  <th>最近任务</th>
                  <th>耗时</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {list.map((x) => (
                  <tr key={x.datasetCode}>
                    <td>
                      <b>{x.name || x.datasetCode}</b>
                      <div className="muted" style={{ fontSize: 11 }}>{x.datasetCode}</div>
                    </td>
                    <td className="muted">{x.mode === 'keys' ? '表级全量' : x.mode === 'related' ? '关联' : '列表'}</td>
                    <td className="muted">{x.endpointCode || '—'}</td>
                    <td>{x.recordCount.toLocaleString()}</td>
                    <td style={{ minWidth: 210, whiteSpace: 'normal' }}>
                      <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
                      <select
                        className="select"
                        value={x.scheduleType || 'manual'}
                        onChange={(e) => {
                          const type = e.target.value
                          saveSchedule(x.datasetCode, {
                            scheduleType: type,
                            scheduleTime: x.scheduleTime || '02:00',
                            scheduleWeekday: x.scheduleWeekday || 7,
                            scheduleIntervalMin: x.scheduleIntervalMin || 60,
                          })
                        }}
                      >
                        <option value="manual">手动</option>
                        <option value="interval">每隔</option>
                        <option value="daily">每天</option>
                        <option value="weekly">每周</option>
                      </select>
                      {(x.scheduleType === 'daily' || x.scheduleType === 'weekly') && (
                        <>
                          <input className="input" style={{ width: 108 }} type="time"
                            value={x.scheduleTime || '02:00'}
                            onChange={(e) => saveSchedule(x.datasetCode, {
                              scheduleType: x.scheduleType,
                              scheduleTime: e.target.value,
                              scheduleWeekday: x.scheduleWeekday || 7,
                              scheduleIntervalMin: x.scheduleIntervalMin || 60,
                            })} />
                          {x.scheduleType === 'weekly' && (
                            <select className="select" style={{ width: 88 }}
                              value={String(x.scheduleWeekday || 7)}
                              onChange={(e) => saveSchedule(x.datasetCode, {
                                scheduleType: x.scheduleType,
                                scheduleTime: x.scheduleTime || '02:00',
                                scheduleWeekday: Number(e.target.value),
                                scheduleIntervalMin: x.scheduleIntervalMin || 60,
                              })}>
                              {[1,2,3,4,5,6,7].map((d) => (
                                <option key={d} value={d}>{['周一','周二','周三','周四','周五','周六','周日'][d-1]}</option>
                              ))}
                            </select>
                          )}
                        </>
                      )}
                      {x.scheduleType === 'interval' && (
                        <>
                          <input className="input" style={{ width: 90 }} type="number" min={5}
                            value={x.scheduleIntervalMin || 60}
                            onChange={(e) => saveSchedule(x.datasetCode, {
                              scheduleType: x.scheduleType,
                              scheduleTime: x.scheduleTime || '02:00',
                              scheduleWeekday: x.scheduleWeekday || 7,
                              scheduleIntervalMin: Number(e.target.value) || 60,
                            })} />
                          <span className="muted">分钟</span>
                        </>
                      )}
                      </div>
                      {x.nextScheduleRun && (
                        <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>下次 {fmtTime(x.nextScheduleRun)}</div>
                      )}
                    </td>
                    <td className="muted">{fmtTime(x.lastSyncedAt)}</td>
                    <td>
                      {x.lastJobStatus ? (
                        <>
                          <span style={{ color: x.lastJobStatus === 'success' ? 'var(--ok)' : x.lastJobStatus === 'failed' ? 'var(--danger)' : 'var(--warn)' }}>
                            {x.lastJobStatus === 'success' ? '成功' : x.lastJobStatus === 'failed' ? '失败' : x.lastJobStatus === 'cancelled' ? '已取消' : x.lastJobStatus === 'queued' ? '排队中' : '同步中'}
                          </span>
                          <span className="muted"> · {x.lastJobRows ?? 0} 行</span>
                          {x.lastJobError && <div className="muted" style={{ fontSize: 11 }}>{x.lastJobError}</div>}
                        </>
                      ) : (
                        <span className="muted">未同步</span>
                      )}
                    </td>
                    <td className="muted">{x.lastJobDurationMs != null ? `${x.lastJobDurationMs} ms` : '—'}</td>
                    <td>
                      {x.lastJobStatus === 'running' || x.lastJobStatus === 'queued' ? (
                        <button className="btn sm" onClick={() => stopSync(x.datasetCode)}>停止</button>
                      ) : (
                        <>
                          <button className="btn sm primary" disabled={busy === x.datasetCode || !x.enabled} onClick={() => runSync(x.datasetCode, 'incremental')}>
                            {busy === x.datasetCode ? '同步中…' : '增量同步'}
                          </button>{' '}
                          <button className="btn sm" disabled={busy === x.datasetCode || !x.enabled} title="清库重取全部数据，上游改了历史数据时用" onClick={() => runSync(x.datasetCode, 'full')}>
                            全量同步
                          </button>
                        </>
                      )}
                      {' '}
                      <button className="btn sm" onClick={() => toggleEnabled(x.datasetCode, !x.enabled)}>
                        {x.enabled ? '停用' : '启用'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      <div className="card">
        <h3>最近任务</h3>
        <div className="table-wrap">
          {jobs.length === 0 ? (
            <EmptyState compact variant="config" title="暂无任务" />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>数据集</th>
                  <th>状态</th>
                  <th>行数</th>
                  <th>页数</th>
                  <th>耗时</th>
                  <th>开始</th>
                  <th>结束</th>
                  <th>错误</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((j) => (
                  <tr key={j.id}>
                    <td>{j.id}</td>
                    <td>{j.datasetCode}</td>
                    <td>
                      <span style={{ color: j.status === 'success' ? 'var(--ok)' : j.status === 'failed' ? 'var(--danger)' : 'var(--warn)' }}>
                        {j.status === 'success' ? '成功' : j.status === 'failed' ? '失败' : j.status === 'cancelled' ? '已取消' : j.status === 'queued' ? '排队中' : '同步中'}
                      </span>
                    </td>
                    <td>{j.rowCount}</td>
                    <td>{j.pageCount}</td>
                    <td className="muted">{j.durationMs != null ? `${j.durationMs} ms` : '—'}</td>
                    <td className="muted">{fmtTime(j.startedAt)}</td>
                    <td className="muted">{fmtTime(j.finishedAt)}</td>
                    <td className="muted" style={{ maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis' }}>{j.error || ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="muted" style={{ marginTop: 8, fontSize: 12 }}>
          同步保存上游原始 JSON 结构；报表字段映射与关联配置不变。切换顶栏开关可在「实时 / 本地」间切换查询来源。
        </div>
      </div>
    </>
  )
}
