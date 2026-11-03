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
  const [mode, setMode] = useState('realtime')
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
      setMode(m?.queryMode || 'realtime')
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function runSync(code: string) {
    setBusy(code)
    setError('')
    setMsg('')
    try {
      await api.post(`/api/sync/${code}/run`)
      setMsg(`已触发同步 ${code}，完成后自动刷新`)
      // 轮询几次
      for (let i = 0; i < 20; i++) {
        await new Promise((r) => setTimeout(r, 1500))
        await load()
        const cur = list.find((x) => x.datasetCode === code) || (await api.get<SyncStatus[]>('/api/sync/status')).find((x: any) => x.datasetCode === code)
        if (cur && cur.lastJobStatus && cur.lastJobStatus !== 'running') {
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
                  <th>接口</th>
                  <th>本地行数</th>
                  <th>最近同步</th>
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
                    <td className="muted">{x.endpointCode || '—'}</td>
                    <td>{x.recordCount.toLocaleString()}</td>
                    <td className="muted">{fmtTime(x.lastSyncedAt)}</td>
                    <td>
                      {x.lastJobStatus ? (
                        <>
                          <span style={{ color: x.lastJobStatus === 'success' ? 'var(--ok)' : x.lastJobStatus === 'failed' ? 'var(--danger)' : 'var(--warn)' }}>
                            {x.lastJobStatus === 'success' ? '成功' : x.lastJobStatus === 'failed' ? '失败' : '同步中'}
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
                      <button className="btn sm primary" disabled={busy === x.datasetCode} onClick={() => runSync(x.datasetCode)}>
                        {busy === x.datasetCode ? '同步中…' : '立即同步'}
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
                        {j.status === 'success' ? '成功' : j.status === 'failed' ? '失败' : '同步中'}
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
