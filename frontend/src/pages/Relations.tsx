import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
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

type JoinPair = { leftField: string; rightField: string }

const CARDINALITY = [
  { value: '1-1', label: '一对一' },
  { value: '1-N', label: '一对多（左一行 → 右多行）' },
  { value: 'N-1', label: '多对一（左多行 → 右一行）' },
  { value: 'N-N', label: '多对多' },
]
const JOIN_TYPE = [
  { value: 'left', label: '左连接（保留左表全部）' },
  { value: 'inner', label: '内连接（仅匹配行）' },
]
const FETCH_MODE = [
  { value: 'dual_list', label: '双侧列表再关联', hint: '两边各调 list，内存 hash join' },
  { value: 'child_batch', label: '批量 IN 查询', hint: '收集主表键，子接口 ?ids= 批量拉' },
  { value: 'child_lookup', label: '逐条单查', hint: '收集主表键，子接口 /{id} 并发拉' },
]
const EXPAND_MODE = [
  { value: 'flat', label: '摊平成多行', hint: 'Excel 友好，主字段重复' },
  { value: 'nested', label: '嵌套子列表', hint: '主表一行，子数据放数组' },
]

function labelOf(list: { value: string; label: string }[], v: string) {
  return list.find((x) => x.value === v)?.label || v
}

function parsePairs(json: string): JoinPair[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) && arr.length
      ? arr.map((x) => ({ leftField: x.leftField || '', rightField: x.rightField || '' }))
      : [{ leftField: '', rightField: '' }]
  } catch {
    return [{ leftField: '', rightField: '' }]
  }
}

function parseCfg(json: string): Record<string, any> {
  try {
    const o = JSON.parse(json || '{}')
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}

export default function Relations() {
  const [params] = useSearchParams()
  const reportCode = params.get('report') || ''
  const [rows, setRows] = useState<Rel[]>([])
  const [reports, setReports] = useState<any[]>([])
  const [datasets, setDatasets] = useState<any[]>([])
  const [endpoints, setEndpoints] = useState<any[]>([])
  const [edit, setEdit] = useState<Rel | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [pairs, setPairs] = useState<JoinPair[]>([{ leftField: '', rightField: '' }])
  const [cfg, setCfg] = useState<Record<string, any>>({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [current, setCurrent] = useState(reportCode)

  const dsName = (code: string) => {
    const d = datasets.find((x) => x.code === code)
    return d ? `${d.name}（${d.code}）` : code
  }
  const repName = (code: string) => {
    const r = reports.find((x) => x.code === code)
    return r ? `${r.name}（${r.code}）` : code
  }
  const epName = (code: string) => {
    const e = endpoints.find((x) => x.code === code)
    return e ? `${e.name}（${e.code}）` : code
  }

  async function load(code = current) {
    setLoading(true)
    try {
      setRows(await api.get<Rel[]>(`/api/admin/reports/${code}/relations`))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    Promise.all([
      api.get<any[]>('/api/admin/reports'),
      api.get<any[]>('/api/admin/datasets'),
      api.get<any[]>('/api/admin/endpoints'),
    ]).then(([r, d, e]) => {
      setReports(r)
      setDatasets(d)
      setEndpoints(e)
      const fallback = reportCode || r[0]?.code || ''
      setCurrent(fallback)
      if (fallback) load(fallback).catch((err) => setError(err.message))
    }).catch((err) => setError(err.message))
    if (reportCode) {
      load(reportCode).catch((err) => setError(err.message))
      setCurrent(reportCode)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reportCode])

  function openEdit(rel?: Rel) {
    setError('')
    if (rel) {
      setIsNew(false)
      setEdit({ ...rel })
      setPairs(parsePairs(rel.onFields))
      setCfg(parseCfg(rel.fetchConfig))
    } else {
      setIsNew(true)
      const empty: Rel = {
        code: '',
        reportCode: current,
        leftDataset: '',
        rightDataset: '',
        cardinality: 'N-1',
        joinType: 'left',
        onFields: '[{"leftField":"","rightField":""}]',
        fetchMode: 'dual_list',
        fetchConfig: '{}',
        expandMode: 'flat',
        sort: 0,
      }
      setEdit(empty)
      setPairs([{ leftField: '', rightField: '' }])
      setCfg({ rightEndpoint: endpoints[0]?.code || '' })
    }
  }

  function switchFetchMode(mode: string) {
    if (!edit) return
    setEdit({ ...edit, fetchMode: mode })
    if (mode === 'dual_list') {
      setCfg({ rightEndpoint: cfg.rightEndpoint || endpoints[0]?.code || '' })
    } else if (mode === 'child_batch') {
      setCfg({
        endpoint: cfg.endpoint || endpoints[0]?.code || '',
        keyParam: cfg.keyParam || 'ids',
        maxKeysPerCall: cfg.maxKeysPerCall || 200,
      })
    } else {
      setCfg({
        detailEndpoint: cfg.detailEndpoint || endpoints[0]?.code || '',
        pathParam: cfg.pathParam || 'id',
        concurrency: cfg.concurrency || 8,
      })
    }
  }

  async function save() {
    if (!edit) return
    if (!edit.code?.trim()) {
      setError('请填写关联编码（主键，保存后不可改）')
      return
    }
    if (!edit.leftDataset || !edit.rightDataset) {
      setError('请选择左表和右表')
      return
    }
    if (!pairs.some((p) => p.leftField && p.rightField)) {
      setError('请至少填写一组关联字段')
      return
    }
    try {
      const onFields = JSON.stringify(pairs.filter((p) => p.leftField && p.rightField))
      const payload = { ...edit, onFields, fetchConfig: JSON.stringify(cfg) }
      await api.post('/api/admin/relations', payload)
      setEdit(null)
      await load(payload.reportCode)
      setCurrent(payload.reportCode)
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm('删除这条关联关系？')) return
    await api.del(`/api/admin/relations/${code}`)
    await load()
  }

  const modeHint = useMemo(
    () => FETCH_MODE.find((x) => x.value === edit?.fetchMode)?.hint || '',
    [edit?.fetchMode],
  )

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="relation" size={22} /></div>
          <div>
            <h1>关联关系</h1>
            <p>配置多张表如何对齐：关联字段、对应关系、取数方式</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => openEdit()}>新建关联</button>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      <div className="toolbar">
        <span className="muted">选择报表</span>
        <select
          className="select"
          style={{ minWidth: 280 }}
          value={current}
          onChange={async (e) => {
            setCurrent(e.target.value)
            await load(e.target.value)
          }}
        >
          {reports.map((r) => (
            <option key={r.code} value={r.code}>{r.name}（{r.code}）</option>
          ))}
        </select>
      </div>

      <div className="table-wrap">
        <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
          <thead>
            <tr>
              <th>关联</th>
              <th>左表</th>
              <th>右表</th>
              <th>对应关系</th>
              <th>连接</th>
              <th>取数策略</th>
              <th>关联字段</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const ps = parsePairs(r.onFields)
              return (
                <tr key={r.code}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{r.code}</div>
                    <div className="muted" style={{ fontSize: 12 }}>{repName(r.reportCode)}</div>
                  </td>
                  <td>{dsName(r.leftDataset)}</td>
                  <td>{dsName(r.rightDataset)}</td>
                  <td><span className="badge muted">{labelOf(CARDINALITY, r.cardinality)}</span></td>
                  <td>{labelOf(JOIN_TYPE, r.joinType)}</td>
                  <td><span className="badge ok">{labelOf(FETCH_MODE, r.fetchMode)}</span></td>
                  <td className="mono" style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {ps.map((p) => `${p.leftField} = ${p.rightField}`).join(' 且 ') || '-'}
                  </td>
                  <td>
                    <div className="row">
                      <button className="btn sm" onClick={() => openEdit(r)}>编辑</button>
                      <button className="btn sm danger" onClick={() => remove(r.code)}>删除</button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {loading && rows.length === 0 ? <TableSkeleton rows={5} cols={5} /> : null}
        {rows.length === 0 && !loading && <EmptyState variant="link" title="暂无关联" desc="配置主表与右表的取数策略和关联字段" />}
      </div>

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建关联' : '编辑关联'}</h3>
                <p className="sub">左右表如何对齐、如何取数</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>1 · 基本信息</b>
                  <span>归属与两端数据集</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      关联编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 rel_cr_hang"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>所属报表</label>
                    <select
                      className="select"
                      value={edit.reportCode}
                      onChange={(e) => setEdit({ ...edit, reportCode: e.target.value })}
                    >
                      {reports.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
                    </select>
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>左表（已有结果 / 主表侧）<span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.leftDataset}
                      onChange={(e) => setEdit({ ...edit, leftDataset: e.target.value })}
                    >
                      <option value="">请选择数据集</option>
                      {datasets.map((d) => (
                        <option key={d.code} value={d.code}>{dsName(d.code)}</option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label>右表（要关联进来）<span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.rightDataset}
                      onChange={(e) => setEdit({ ...edit, rightDataset: e.target.value })}
                    >
                      <option value="">请选择数据集</option>
                      {datasets.map((d) => (
                        <option key={d.code} value={d.code}>{dsName(d.code)}</option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>2 · 对应关系</b>
                  <span>会不会摊成多行</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>基数</label>
                    <select
                      className="select"
                      value={edit.cardinality}
                      onChange={(e) => setEdit({ ...edit, cardinality: e.target.value })}
                    >
                      {CARDINALITY.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label>连接方式</label>
                    <select
                      className="select"
                      value={edit.joinType}
                      onChange={(e) => setEdit({ ...edit, joinType: e.target.value })}
                    >
                      {JOIN_TYPE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>一对多时如何展开</label>
                    <select
                      className="select"
                      value={edit.expandMode}
                      onChange={(e) => setEdit({ ...edit, expandMode: e.target.value })}
                    >
                      {EXPAND_MODE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                    </select>
                    <span className="hint">{EXPAND_MODE.find((x) => x.value === edit.expandMode)?.hint}</span>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>3 · 关联字段</b>
                  <span>左右字段值相等则匹配，可多组</span>
                  <button
                    className="btn sm"
                    style={{ marginLeft: 'auto' }}
                    onClick={() => setPairs([...pairs, { leftField: '', rightField: '' }])}
                  >
                    <Icon name="plus" size={12} />加一组
                  </button>
                </div>
                {pairs.map((p, idx) => (
                  <div className="param-row" key={idx} style={{ gridTemplateColumns: '1fr 28px 1fr auto' }}>
                    <input
                      className="input"
                      placeholder="左表字段 completion_id"
                      value={p.leftField}
                      onChange={(e) => setPairs(pairs.map((x, i) => (i === idx ? { ...x, leftField: e.target.value } : x)))}
                    />
                    <span className="muted">=</span>
                    <input
                      className="input"
                      placeholder="右表字段 hang_id"
                      value={p.rightField}
                      onChange={(e) => setPairs(pairs.map((x, i) => (i === idx ? { ...x, rightField: e.target.value } : x)))}
                    />
                    <button
                      className="btn sm danger"
                      disabled={pairs.length <= 1}
                      onClick={() => setPairs(pairs.filter((_, i) => i !== idx))}
                    >
                      删
                    </button>
                  </div>
                ))}
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>4 · 取数策略</b>
                  <span>{modeHint}</span>
                </div>
                <div className="field">
                  <label>如何拉右表数据</label>
                  <select
                    className="select"
                    value={edit.fetchMode}
                    onChange={(e) => switchFetchMode(e.target.value)}
                  >
                    {FETCH_MODE.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
                  </select>
                </div>

                {edit.fetchMode === 'dual_list' && (
                  <div className="field">
                    <label>右表列表接口</label>
                    <select
                      className="select"
                      value={cfg.rightEndpoint || ''}
                      onChange={(e) => setCfg({ ...cfg, rightEndpoint: e.target.value })}
                    >
                      <option value="">请选择接口</option>
                      {endpoints.map((ep) => (
                        <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                      ))}
                    </select>
                  </div>
                )}

                {edit.fetchMode === 'child_batch' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>批量查询接口</label>
                      <select
                        className="select"
                        value={cfg.endpoint || ''}
                        onChange={(e) => setCfg({ ...cfg, endpoint: e.target.value })}
                      >
                        <option value="">请选择接口</option>
                        {endpoints.map((ep) => (
                          <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                        ))}
                      </select>
                    </div>
                    <div className="field">
                      <label>批量键参数名</label>
                      <input
                        className="input"
                        value={cfg.keyParam || 'ids'}
                        onChange={(e) => setCfg({ ...cfg, keyParam: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>单次最多键数</label>
                      <input
                        className="input"
                        type="number"
                        value={cfg.maxKeysPerCall || 200}
                        onChange={(e) => setCfg({ ...cfg, maxKeysPerCall: Number(e.target.value) })}
                      />
                    </div>
                  </div>
                )}

                {edit.fetchMode === 'child_lookup' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>单条查询接口</label>
                      <select
                        className="select"
                        value={cfg.detailEndpoint || cfg.endpoint || ''}
                        onChange={(e) => setCfg({ ...cfg, detailEndpoint: e.target.value })}
                      >
                        <option value="">请选择接口</option>
                        {endpoints.map((ep) => (
                          <option key={ep.code} value={ep.code}>{epName(ep.code)}</option>
                        ))}
                      </select>
                    </div>
                    <div className="field">
                      <label>路径参数名</label>
                      <input
                        className="input"
                        placeholder="id"
                        value={cfg.pathParam || 'id'}
                        onChange={(e) => setCfg({ ...cfg, pathParam: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>并发数</label>
                      <input
                        className="input"
                        type="number"
                        min={1}
                        max={32}
                        value={cfg.concurrency || 8}
                        onChange={(e) => setCfg({ ...cfg, concurrency: Number(e.target.value) })}
                      />
                    </div>
                  </div>
                )}
              </div>
            </div>

            <div className="modal-footer">
              <button className="btn" onClick={() => setEdit(null)}>取消</button>
              <button className="btn primary" onClick={save}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
