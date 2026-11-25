import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'
import {
  SyncJob,
  SyncStatus,
  fmtDuration,
  fmtTime,
  statusInfo,
  triggerInfo,
} from './syncUtils'

export default function SyncLogs() {
  const [params, setParams] = useSearchParams()
  const initDataset = params.get('dataset') || ''

  const [datasets, setDatasets] = useState<SyncStatus[]>([])
  const [jobs, setJobs] = useState<SyncJob[]>([])
  const [datasetCode, setDatasetCode] = useState(initDataset)
  const [status, setStatus] = useState('all')
  const [trigger, setTrigger] = useState('all')
  const [limit, setLimit] = useState(100)
  const [loading, setLoading] = useState(true)
  const [rerunning, setRerunning] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [msg, setMsg] = useState('')
  const [inspectJob, setInspectJob] = useState<SyncJob | null>(null)

  async function loadJobs(ds = datasetCode, st = status, tr = trigger, lim = limit) {
    setLoading(true)
    setError('')
    try {
      const qs = new URLSearchParams()
      if (ds) qs.set('datasetCode', ds)
      if (st && st !== 'all') qs.set('status', st)
      if (tr && tr !== 'all') qs.set('trigger', tr)
      qs.set('limit', String(lim))
      const data = await api.get<SyncJob[]>(`/api/sync/jobs?${qs.toString()}`)
      setJobs(data || [])
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    api.get<SyncStatus[]>('/api/sync/status').then((list) => setDatasets(list || [])).catch(() => {})
  }, [])

  useEffect(() => {
    loadJobs(datasetCode, status, trigger, limit)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [datasetCode, status, trigger, limit])

  function changeDataset(code: string) {
    setDatasetCode(code)
    if (code) setParams({ dataset: code }, { replace: true })
    else setParams({}, { replace: true })
  }

  async function rerunJob(job: SyncJob) {
    const t = triggerInfo(job.trigger)
    setRerunning(job.id)
    setError('')
    setMsg('')
    try {
      await api.post(`/api/sync/${encodeURIComponent(job.datasetCode)}/run`, {
        strategy: t.isFull ? 'full' : 'incremental',
      })
      setMsg(`已重新触发 ${job.datasetName || job.datasetCode}（${t.isFull ? '全量' : '增量'}同步）`)
      setTimeout(() => loadJobs(), 1200)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setRerunning(null)
    }
  }

  const stats = useMemo(() => {
    const total = jobs.length
    const success = jobs.filter((j) => j.status === 'success').length
    const failed = jobs.filter((j) => j.status === 'failed').length
    const durations = jobs.map((j) => j.durationMs).filter((d): d is number => typeof d === 'number' && d >= 0)
    const avgMs = durations.length ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length) : null
    const rate = total > 0 ? `${Math.round((success / total) * 100)}%` : '—'
    return { total, success, failed, avgMs, rate }
  }, [jobs])

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="clock" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>同步记录</h1>
            <p className="one-line">独立查看全部历史同步执行流水、耗时、触发来源与异常排查详情</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm" to="/sync">
            <Icon name="sync" size={13} />数据同步配置
          </Link>
          <button type="button" className="btn sm primary" onClick={() => loadJobs()} disabled={loading}>
            <Icon name="refresh" size={13} />{loading ? '刷新中…' : '刷新'}
          </button>
        </div>
      </div>

      {/* 统计概览 */}
      <div className="sync-kpi-strip">
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">当前筛选流水</span>
          <strong className="sync-kpi-val">{stats.total} <small>条</small></strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">执行成功率</span>
          <strong className="sync-kpi-val tone-ok">
            {stats.rate} <small>({stats.success} 成功)</small>
          </strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">失败记录</span>
          <strong className={`sync-kpi-val ${stats.failed > 0 ? 'tone-err' : ''}`}>
            {stats.failed} <small>条</small>
          </strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">平均耗时</span>
          <strong className="sync-kpi-val">{fmtDuration(stats.avgMs)}</strong>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {msg && (
        <div className="sync-notice">
          <Icon name="check" size={14} />
          <span>{msg}</span>
        </div>
      )}

      {/* 筛选栏 */}
      <div className="sync-toolbar">
        <select
          className="select sync-dataset-select"
          value={datasetCode}
          onChange={(e) => changeDataset(e.target.value)}
          aria-label="按数据集筛选"
        >
          <option value="">全部数据集</option>
          {datasets.map((d) => (
            <option key={d.datasetCode} value={d.datasetCode}>
              {d.name || d.datasetCode} ({d.datasetCode})
            </option>
          ))}
        </select>

        <div className="sync-filter-pills">
          {([
            ['all', '全部状态'],
            ['success', '成功'],
            ['failed', '失败'],
            ['active', '进行中'],
            ['cancelled', '已取消'],
          ] as const).map(([val, label]) => (
            <button
              type="button"
              key={val}
              className={`sync-pill${status === val ? ' active' : ''}`}
              onClick={() => setStatus(val)}
            >
              {label}
            </button>
          ))}
        </div>

        <select
          className="select sync-compact-select"
          value={trigger}
          onChange={(e) => setTrigger(e.target.value)}
          aria-label="按触发方式筛选"
        >
          <option value="all">全部触发</option>
          <option value="schedule">定时调度</option>
          <option value="manual">手动触发</option>
        </select>

        <select
          className="select sync-compact-select"
          value={String(limit)}
          onChange={(e) => setLimit(Number(e.target.value))}
          aria-label="显示条数"
        >
          <option value="50">近 50 条</option>
          <option value="100">近 100 条</option>
          <option value="200">近 200 条</option>
        </select>
      </div>

      <div className="table-wrap sync-logs-table">
        {loading && jobs.length === 0 ? (
          <TableSkeleton rows={8} cols={7} />
        ) : jobs.length === 0 ? (
          <EmptyState
            variant="config"
            title="暂无符合条件的同步记录"
            desc="可放宽筛选条件，或前往「数据同步」触发一次同步任务"
          />
        ) : (
          <table>
            <thead>
              <tr>
                <th style={{ width: 64 }}>#ID</th>
                <th>数据集</th>
                <th>触发方式</th>
                <th>状态</th>
                <th className="num">行数 / 页数</th>
                <th className="num">耗时</th>
                <th>开始时间</th>
                <th>错误信息</th>
                <th style={{ width: 128 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((j) => {
                const st = statusInfo(j.status)
                const tr = triggerInfo(j.trigger)
                return (
                  <tr key={j.id}>
                    <td className="muted mono">#{j.id}</td>
                    <td>
                      <Link to={`/sync/${j.datasetCode}`} className="sync-ds-link">
                        {j.datasetName || j.datasetCode}
                      </Link>
                      <div className="muted mono" style={{ fontSize: 11 }}>{j.datasetCode}</div>
                    </td>
                    <td>
                      <span className={`badge ${tr.isSchedule ? 'muted' : 'ok'}`}>{tr.label}</span>
                    </td>
                    <td>
                      <span className={`badge ${st.tone}`}>{st.label}</span>
                    </td>
                    <td className="num mono">
                      <b>{Number(j.rowCount || 0).toLocaleString('zh-CN')}</b>
                      <span className="muted"> / {j.pageCount} 页</span>
                    </td>
                    <td className="num mono muted">{fmtDuration(j.durationMs)}</td>
                    <td className="mono muted" style={{ fontSize: 12 }}>
                      <div>{fmtTime(j.startedAt)}</div>
                      {j.finishedAt && (
                        <div style={{ fontSize: 10, opacity: 0.7 }}>止 {fmtTime(j.finishedAt).slice(11)}</div>
                      )}
                    </td>
                    <td style={{ maxWidth: 220 }}>
                      {j.error ? (
                        <button
                          type="button"
                          className="sync-err-btn"
                          title={j.error}
                          onClick={() => setInspectJob(j)}
                        >
                          {j.error}
                        </button>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td>
                      <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        <button
                          type="button"
                          className="btn sm"
                          disabled={rerunning === j.id}
                          onClick={() => rerunJob(j)}
                        >
                          {rerunning === j.id ? '触发中' : '重跑'}
                        </button>
                        <Link className="btn sm ghost" to={`/sync/${j.datasetCode}`}>配置</Link>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {inspectJob && (
        <div className="modal-backdrop" onClick={() => setInspectJob(null)}>
          <div className="modal" style={{ maxWidth: 640 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>任务 #{inspectJob.id} 异常详情</h3>
                <p className="sub">
                  {inspectJob.datasetName || inspectJob.datasetCode}（{inspectJob.datasetCode}）· {fmtTime(inspectJob.startedAt)}
                </p>
              </div>
              <button type="button" className="btn sm ghost" onClick={() => setInspectJob(null)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="sync-code-block" style={{ marginTop: 0 }}>
                <div className="sync-code-label">错误堆栈 / 信息</div>
                <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{inspectJob.error}</pre>
              </div>
            </div>
            <div className="modal-footer">
              <Link className="btn" to={`/sync/${inspectJob.datasetCode}`}>前往检查配置</Link>
              <button
                type="button"
                className="btn primary"
                onClick={() => {
                  const target = inspectJob
                  setInspectJob(null)
                  rerunJob(target)
                }}
              >
                重新执行同步
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
