import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'
import {
  SyncStatus,
  fmtDuration,
  fmtShortTime,
  formatScheduleSummary,
  modeLabel,
  statusInfo,
} from './syncUtils'

export default function Sync() {
  const [list, setList] = useState<SyncStatus[]>([])
  const [mode, setMode] = useState('local')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [msg, setMsg] = useState('')

  const [kw, setKw] = useState('')
  const [statusFilter, setStatusFilter] = useState<'all' | 'enabled' | 'disabled' | 'failed'>('all')
  const [sourceFilter, setSourceFilter] = useState('all')
  const aliveRef = useRef(true)

  async function load(): Promise<SyncStatus[]> {
    setLoading(true)
    setError('')
    try {
      const [s, m] = await Promise.all([
        api.get<SyncStatus[]>('/api/sync/status'),
        api.get<any>('/api/settings/query-mode'),
      ])
      const fresh = s || []
      if (aliveRef.current) {
        setList(fresh)
        setMode(m?.queryMode || 'local')
      }
      return fresh
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
      return []
    } finally {
      if (aliveRef.current) setLoading(false)
    }
  }

  useEffect(() => {
    aliveRef.current = true
    load()
    return () => { aliveRef.current = false }
  }, [])

  async function runAll(strategy: 'incremental' | 'full') {
    if (strategy === 'full' && !confirm('确定执行「一键全量同步」？将清库重拉所有已启用数据集。')) return
    setBusy('ALL')
    setError('')
    setMsg('')
    try {
      await api.post('/api/sync/run-all', { strategy })
      setMsg(strategy === 'full' ? '已开始全量同步（按顺序执行），页面将自动刷新进度…' : '已开始增量同步（按顺序执行），页面将自动刷新进度…')
      for (let i = 0; i < 60; i++) {
        await new Promise((r) => setTimeout(r, 3000))
        if (!aliveRef.current) return
        const fresh = await load()
        const active = fresh.some((x) => x.lastJobStatus === 'running' || x.lastJobStatus === 'queued')
        if (!active) {
          if (aliveRef.current) {
            setMsg(strategy === 'full' ? '一键全量同步已完成' : '一键增量同步已完成')
          }
          break
        }
      }
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
    } finally {
      if (aliveRef.current) setBusy('')
    }
  }

  async function runSync(code: string, strategy: 'incremental' | 'full') {
    setBusy(code)
    setError('')
    setMsg('')
    try {
      await api.post(`/api/sync/${code}/run`, { strategy })
      setMsg(`已触发 ${code} ${strategy === 'full' ? '全量' : '增量'}同步，完成后自动更新`)
      for (let i = 0; i < 40; i++) {
        await new Promise((r) => setTimeout(r, 1500))
        if (!aliveRef.current) return
        const fresh = await load()
        const cur = fresh.find((x) => x.datasetCode === code)
        if (cur && cur.lastJobStatus && cur.lastJobStatus !== 'running' && cur.lastJobStatus !== 'queued') {
          if (aliveRef.current) {
            const st = statusInfo(cur.lastJobStatus).label
            setMsg(`${cur.name || code} 同步${st}（${cur.lastJobRows ?? 0} 行）`)
          }
          break
        }
      }
    } catch (e: any) {
      if (aliveRef.current) setError(e.message)
    } finally {
      if (aliveRef.current) setBusy('')
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

  async function toggleEnabled(item: SyncStatus) {
    try {
      await api.post(`/api/sync/${item.datasetCode}/enabled`, { enabled: !item.enabled })
      setMsg(`${item.name || item.datasetCode} 已${item.enabled ? '停用' : '启用'}`)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  const sources = useMemo(() => {
    const map = new Map<string, string>()
    for (const x of list) {
      if (x.dataSourceCode) map.set(x.dataSourceCode, x.dataSourceName || x.dataSourceCode)
    }
    return Array.from(map.entries()).map(([code, name]) => ({ code, name }))
  }, [list])

  const stats = useMemo(() => {
    const total = list.length
    const enabled = list.filter((x) => x.enabled).length
    const scheduled = list.filter((x) => x.enabled && x.scheduleType && x.scheduleType !== 'manual').length
    const failed = list.filter((x) => x.lastJobStatus === 'failed').length
    const running = list.filter((x) => x.lastJobStatus === 'running' || x.lastJobStatus === 'queued').length
    const totalRows = list.reduce((acc, x) => acc + Number(x.recordCount || 0), 0)
    return { total, enabled, scheduled, failed, running, totalRows }
  }, [list])

  const filtered = useMemo(() => {
    const q = kw.trim().toLowerCase()
    return list.filter((x) => {
      if (statusFilter === 'enabled' && !x.enabled) return false
      if (statusFilter === 'disabled' && x.enabled) return false
      if (statusFilter === 'failed' && x.lastJobStatus !== 'failed') return false
      if (sourceFilter !== 'all' && x.dataSourceCode !== sourceFilter) return false
      if (q) {
        const hay = `${x.name || ''} ${x.datasetCode} ${x.endpointCode || ''} ${x.dataSourceName || ''}`.toLowerCase()
        if (!hay.includes(q)) return false
      }
      return true
    })
  }, [list, kw, statusFilter, sourceFilter])

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="sync" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>数据同步</h1>
            <p className="one-line">管理各数据集的本地同步计划与手动触发 · 点「配置」进入独立参数与调度设置页</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm" to="/sync-logs">
            <Icon name="clock" size={13} />同步记录
          </Link>
          <button type="button" className="btn sm primary" onClick={() => runAll('incremental')} disabled={busy === 'ALL'}>
            <Icon name="bolt" size={13} />{busy === 'ALL' ? '同步中…' : '一键增量'}
          </button>
          <button type="button" className="btn sm" title="清库重拉全部已启用数据集" onClick={() => runAll('full')} disabled={busy === 'ALL'}>
            一键全量
          </button>
          <button type="button" className="btn sm ghost" onClick={load} disabled={loading} title="刷新列表">
            <Icon name="refresh" size={13} />刷新
          </button>
        </div>
      </div>

      {/* 轻量概览条 */}
      <div className="sync-kpi-strip">
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">同步对象</span>
          <strong className="sync-kpi-val">
            {stats.enabled} <small>/ {stats.total} 已启用</small>
          </strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">本地数据总量</span>
          <strong className="sync-kpi-val">
            {stats.totalRows.toLocaleString('zh-CN')} <small>行</small>
          </strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">定时计划</span>
          <strong className="sync-kpi-val">
            {stats.scheduled} <small>张自动调度 · {mode === 'local' ? '本地模式生效中' : '当前为实时模式'}</small>
          </strong>
        </div>
        <div className="sync-kpi-cell">
          <span className="sync-kpi-label">运行状态</span>
          <strong className={`sync-kpi-val ${stats.failed > 0 ? 'tone-err' : 'tone-ok'}`}>
            {stats.running > 0
              ? `${stats.running} 项同步中`
              : stats.failed > 0
                ? `${stats.failed} 项异常`
                : '全部正常'}
          </strong>
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
        <div className="sync-search-box">
          <Icon name="search" size={14} />
          <input
            className="input"
            placeholder="搜索数据集名称、编码或接口…"
            value={kw}
            onChange={(e) => setKw(e.target.value)}
          />
        </div>
        <div className="sync-filter-pills">
          {([
            ['all', `全部 (${list.length})`],
            ['enabled', `已启用 (${stats.enabled})`],
            ['disabled', `已停用 (${stats.total - stats.enabled})`],
            ['failed', `异常 (${stats.failed})`],
          ] as const).map(([val, label]) => (
            <button
              type="button"
              key={val}
              className={`sync-pill${statusFilter === val ? ' active' : ''}${val === 'failed' && stats.failed > 0 ? ' danger-pill' : ''}`}
              onClick={() => setStatusFilter(val)}
            >
              {label}
            </button>
          ))}
        </div>
        {sources.length > 1 && (
          <select
            className="select sync-source-select"
            value={sourceFilter}
            onChange={(e) => setSourceFilter(e.target.value)}
            aria-label="按数据源筛选"
          >
            <option value="all">全部数据源</option>
            {sources.map((s) => (
              <option key={s.code} value={s.code}>{s.name}</option>
            ))}
          </select>
        )}
      </div>

      {/* 简洁清爽的配置列表 */}
      <div className="table-wrap sync-list-table">
        {loading && list.length === 0 ? (
          <TableSkeleton rows={6} cols={6} />
        ) : filtered.length === 0 ? (
          <EmptyState
            variant="config"
            title={list.length === 0 ? '暂无同步配置' : '没有匹配的同步对象'}
            desc={list.length === 0 ? '系统启动后会自动初始化核心数据集同步配置' : '可尝试清空搜索词或切换状态筛选'}
          />
        ) : (
          <table>
            <thead>
              <tr>
                <th>数据集 / 来源</th>
                <th>同步策略</th>
                <th className="num">本地行数</th>
                <th>定时计划</th>
                <th>最近执行</th>
                <th>启用</th>
                <th style={{ width: 224 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((x) => {
                const st = statusInfo(x.lastJobStatus)
                const isRunning = x.lastJobStatus === 'running' || x.lastJobStatus === 'queued'
                const isBusy = busy === x.datasetCode || busy === 'ALL'
                return (
                  <tr key={x.datasetCode} className={!x.enabled ? 'sync-row-disabled' : ''}>
                    <td>
                      <div className="sync-ds-title">
                        <Link to={`/sync/${x.datasetCode}`} className="sync-ds-link">
                          {x.name || x.datasetCode}
                        </Link>
                        {x.dataSourceName && <span className="sync-src-tag">{x.dataSourceName}</span>}
                      </div>
                      <div className="muted mono" style={{ fontSize: 11, marginTop: 2 }}>
                        {x.datasetCode}{x.endpointCode ? ` · ${x.endpointCode}` : ''}
                      </div>
                    </td>
                    <td>
                      <span className="badge muted">{modeLabel(x)}</span>
                    </td>
                    <td className="num mono" style={{ fontWeight: 600 }}>
                      {Number(x.recordCount || 0).toLocaleString('zh-CN')}
                    </td>
                    <td>
                      <div className="sync-sched-main">
                        <span className={`sync-dot ${x.enabled && x.scheduleType !== 'manual' ? 'on' : 'off'}`} />
                        <span>{formatScheduleSummary(x)}</span>
                      </div>
                      <div className="muted mono" style={{ fontSize: 11, marginTop: 2 }}>
                        {x.enabled && x.nextScheduleRun ? `下次 ${fmtShortTime(x.nextScheduleRun)}` : '未启用定时'}
                      </div>
                    </td>
                    <td>
                      <div className="sync-last-row">
                        <span className={`badge ${st.tone}`}>{st.label}</span>
                        {x.lastJobStatus && (
                          <span className="muted mono" style={{ fontSize: 11 }}>
                            {x.lastJobRows ?? 0} 行 · {fmtDuration(x.lastJobDurationMs)}
                          </span>
                        )}
                      </div>
                      <div
                        className={`muted${x.lastJobError ? ' sync-err-line' : ' mono'}`}
                        style={{ fontSize: 11, marginTop: 2 }}
                        title={x.lastJobError || x.lastSyncedAt || ''}
                      >
                        {x.lastJobError ? x.lastJobError : fmtShortTime(x.lastSyncedAt)}
                      </div>
                    </td>
                    <td>
                      <button
                        type="button"
                        role="switch"
                        aria-checked={x.enabled}
                        className={`sync-switch${x.enabled ? ' on' : ''}`}
                        onClick={() => toggleEnabled(x)}
                        title={x.enabled ? '点击停用同步' : '点击启用同步'}
                      >
                        <span className="sync-switch-thumb" />
                      </button>
                    </td>
                    <td>
                      <div className="row" style={{ gap: 6, flexWrap: 'nowrap' }}>
                        <Link className="btn sm" to={`/sync/${x.datasetCode}`}>配置</Link>
                        {isRunning ? (
                          <button type="button" className="btn sm danger" onClick={() => stopSync(x.datasetCode)}>
                            停止
                          </button>
                        ) : (
                          <>
                            <button
                              type="button"
                              className="btn sm primary"
                              disabled={isBusy || !x.enabled}
                              onClick={() => runSync(x.datasetCode, 'incremental')}
                            >
                              {busy === x.datasetCode ? '同步中' : '增量'}
                            </button>
                            <button
                              type="button"
                              className="btn sm ghost"
                              disabled={isBusy || !x.enabled}
                              title="清库重拉该数据集全部数据"
                              onClick={() => runSync(x.datasetCode, 'full')}
                            >
                              全量
                            </button>
                          </>
                        )}
                        <Link
                          className="btn sm ghost"
                          to={`/sync-logs?dataset=${encodeURIComponent(x.datasetCode)}`}
                          title="查看该数据集的同步记录"
                        >
                          记录
                        </Link>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
