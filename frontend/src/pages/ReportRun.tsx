import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useRef } from 'react'
import { api, getUser } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Col = { key: string; label: string; from?: string }
type Filter = { key: string; label: string; op?: string; type?: string; options?: { value: string; label: string }[] }
type Trace = { mode: string; endpoint: string; url: string; rows: number; ms: number }

const MODE_LABEL: Record<string, string> = {
  root: '主表',
  dual_list: '双侧列表',
  child_batch: '批量 IN',
  child_lookup: '逐条单查',
}

function DateInput({ value, onChange, placeholder }: {
  value?: string
  onChange: (v: string) => void
  placeholder?: string
}) {
  const ref = useRef<HTMLInputElement>(null)
  const open = () => {
    const el = ref.current as any
    if (el?.showPicker) {
      try { el.showPicker() } catch { el.focus() }
    } else {
      el?.focus()
    }
  }
  return (
    <div className="date-field" onClick={open}>
      <input
        ref={ref}
        className="input"
        type="date"
        value={value || ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
      <span className="date-icon"><Icon name="calendar" size={15} /></span>
    </div>
  )
}

export default function ReportRun() {
  const { code = '' } = useParams()
  const isAdmin = getUser()?.role === 'admin'
  const [meta, setMeta] = useState<any>(null)
  const [columns, setColumns] = useState<Col[]>([])
  const [filtersDef, setFiltersDef] = useState<Filter[]>([])
  const [values, setValues] = useState<Record<string, any>>({})
  const [rows, setRows] = useState<any[]>([])
  const [trace, setTrace] = useState<Trace[]>([])
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const [durationMs, setDurationMs] = useState(0)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const [pageSize, setPageSize] = useState(50)
  const [pageOpt, setPageOpt] = useState<number[]>([20, 50, 100, 200])
  const [showJump, setShowJump] = useState(true)
  const [pageInput, setPageInput] = useState('1')

  useEffect(() => {
    api.get<any>(`/api/reports/${code}/meta`).then((m) => {
      setMeta(m)
      try { setColumns(JSON.parse(m.fields || '[]')) } catch { setColumns([]) }
      try { setFiltersDef(JSON.parse(m.filters || '[]')) } catch { setFiltersDef([]) }
      try {
        const pg = JSON.parse(m.pagination || '{}')
        if (pg.defaultPageSize) setPageSize(Number(pg.defaultPageSize))
        if (Array.isArray(pg.pageSizeOptions) && pg.pageSizeOptions.length) {
          setPageOpt(pg.pageSizeOptions.map(Number))
        }
        if (pg.showJump === false) setShowJump(false)
      } catch { /* default */ }
    }).catch((e) => setError(e.message))
  }, [code])

  const defaultFilters = useMemo(() => {
    const init: Record<string, any> = {}
    for (const f of filtersDef) {
      if (f.op === 'date_range' || f.type === 'date_range') {
        init[f.key] = { start: '', end: '' }
      } else {
        init[f.key] = ''
      }
    }
    return init
  }, [filtersDef])

  useEffect(() => {
    setValues(defaultFilters)
  }, [defaultFilters])

  async function run(nextPage = 1, size = pageSize) {
    setLoading(true)
    setError('')
    setPageInput(String(nextPage))
    try {
      const body = { filters: values, page: nextPage, pageSize: size }
      const result = isAdmin
        ? await api.post<any>(`/api/reports/${code}/debug`, body)
        : await api.post<any>(`/api/reports/${code}/query`, body)
      setRows(result.rows || [])
      setColumns(result.columns?.length ? result.columns : columns)
      setTrace(result.trace || [])
      setTotal(result.total || 0)
      setDurationMs(result.durationMs || 0)
      setPage(result.page || nextPage)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function exportExcel() {
    setExporting(true)
    setError('')
    try {
      const token = localStorage.getItem('report_hub_token') || ''
      const res = await fetch(`/api/reports/${code}/export`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ filters: values }),
      })
      if (!res.ok) {
        const text = await res.text()
        let msg = '导出失败'
        try {
          const j = JSON.parse(text)
          msg = j.error || msg
        } catch {
          msg = text || msg
        }
        throw new Error(msg)
      }
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `${meta?.name || code}.xlsx`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setExporting(false)
    }
  }

  useEffect(() => {
    if (code) run(1, pageSize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, filtersDef, pageSize])

  function setFilter(key: string, part: string, v: string) {
    setValues((prev) => {
      const cur = prev[key]
      if (cur && typeof cur === 'object') return { ...prev, [key]: { ...cur, [part]: v } }
      return { ...prev, [key]: v }
    })
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="report" size={22} /></div>
          <div>
            <h1>{meta?.name || code}</h1>
            <p>{meta?.description || '多源数据实时关联查询'}</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn" to="/reports"><Icon name="arrowLeft" size={14} />返回列表</Link>
          <button className="btn" onClick={exportExcel} disabled={exporting || loading}>
            <Icon name="download" size={14} />
            {exporting ? '导出中…' : '导出 Excel'}
          </button>
          <button className="btn primary" onClick={() => run(1)} disabled={loading}>
            <Icon name="play" size={14} />
            {loading ? '查询中…' : '执行查询'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="card" style={{ marginBottom: 14 }}>
        <div className="toolbar" style={{ marginBottom: 0 }}>
          {filtersDef.map((f) => {
            const val = values[f.key]
            const isRange = f.op === 'date_range' || f.type === 'date_range' || (val && typeof val === 'object')
            return (
              <div className="row" key={f.key}>
                <span className="muted" style={{ fontSize: 12 }}>{f.label || f.key}</span>
                {isRange ? (
                  <>
                    <DateInput
                      value={val?.start || ''}
                      placeholder="开始日期"
                      onChange={(v) => setFilter(f.key, 'start', v)}
                    />
                    <span className="muted">至</span>
                    <DateInput
                      value={val?.end || ''}
                      placeholder="结束日期"
                      onChange={(v) => setFilter(f.key, 'end', v)}
                    />
                  </>
                ) : (f.op === 'select' || f.type === 'select') && f.options?.length ? (
                  <select
                    className="select"
                    value={val || ''}
                    onChange={(e) => setValues((prev) => ({ ...prev, [f.key]: e.target.value }))}
                  >
                    <option value="">全部</option>
                    {f.options.map((o) => (
                      <option key={String(o.value)} value={o.value}>{o.label || o.value}</option>
                    ))}
                  </select>
                ) : (
                  <input
                    className="input"
                    value={val || ''}
                    onChange={(e) => setFilter(f.key, 'value', e.target.value)}
                  />
                )}
              </div>
            )
          })}
          {filtersDef.length === 0 && <span className="muted">无筛选条件</span>}
        </div>
      </div>

      <div className="metrics" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
        <div className="card metric">
          <div className="label"><span>结果行数</span></div>
          <div className="value">{total}</div>
          <div className="hint">关联汇总后的行数</div>
        </div>
        <div className="card metric">
          <div className="label"><span>耗时</span></div>
          <div className="value">{durationMs}<span style={{ fontSize: 14 }}> ms</span></div>
          <div className="hint">含上游接口调用</div>
        </div>
        <div className="card metric">
          <div className="label"><span>上游调用</span></div>
          <div className="value">{trace.length}</div>
          <div className="hint">主表 + 各关联取数</div>
        </div>
      </div>

      <div className="table-wrap" style={{ marginBottom: 14 }}>
        {loading && rows.length === 0 ? (
          <div style={{ padding: 8 }}><TableSkeleton rows={6} cols={Math.min(columns.length || 4, 8)} /></div>
        ) : (
        <table>
          <thead>
            <tr>
              {columns.map((c) => <th key={c.key}>{c.label || c.key}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, idx) => (
              <tr key={idx}>
                {columns.map((c) => (
                  <td key={c.key}>{row[c.key] ?? '-'}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        )}
        {rows.length === 0 && !loading && (
          <EmptyState
            variant="search"
            title="无数据"
            desc="换个日期或状态再查，或检查接口返回"
          />
        )}
      </div>

      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 14 }}>
        <div className="row">
          <span className="muted">共 {total} 行</span>
          <select
            className="select"
            style={{ width: 110 }}
            value={String(pageSize)}
            onChange={(e) => {
              const n = Number(e.target.value)
              setPageSize(n)
              run(1, n)
            }}
          >
            {pageOpt.map((n) => <option key={n} value={n}>{n} 条/页</option>)}
          </select>
        </div>
        <div className="row">
          <button className="btn sm" disabled={page <= 1 || loading} onClick={() => run(page - 1)}>上一页</button>
          <input
            className="input"
            style={{ width: 70, textAlign: 'center' }}
            value={pageInput}
            onChange={(e) => setPageInput(e.target.value.replace(/\D/g, ''))}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                const n = Math.max(1, Number(pageInput) || 1)
                run(n)
              }
            }}
          />
          <span className="muted">/ {Math.max(1, Math.ceil(total / pageSize))}</span>
          <button
            className="btn sm"
            disabled={page * pageSize >= total || loading}
            onClick={() => run(page + 1)}
          >
            下一页
          </button>
          {showJump && (
            <button
              className="btn sm"
              disabled={loading}
              onClick={() => run(Math.max(1, Number(pageInput) || 1))}
            >
              跳转
            </button>
          )}
        </div>
      </div>

      {isAdmin && (
        <div className="card">
          <h3>取数调用链</h3>
          <div className="sub">仅管理员可见 · 每一步调了哪个接口、多少行、多久</div>
          <div className="trace-item" style={{ color: 'var(--muted)' }}>
            <span>环节</span><span>接口</span><span>请求</span><span>行数</span><span>耗时</span>
          </div>
          {trace.map((t, i) => (
            <div className="trace-item" key={i}>
              <span className="badge muted">{MODE_LABEL[t.mode] || t.mode}</span>
              <span className="mono">{t.endpoint}</span>
              <span className="mono" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.url}</span>
              <span>{t.rows}</span>
              <span>{t.ms}ms</span>
            </div>
          ))}
          {trace.length === 0 && <EmptyState compact variant="config" title="执行查询后展示" />}
        </div>
      )}
    </>
  )
}
