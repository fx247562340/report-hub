import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Report = {
  code: string
  name: string
  description?: string
  rootDataset: string
  fieldsJson: string
  filtersJson: string
  orderByJson?: string
  calcsJson?: string
  paginationJson?: string
  enabled: boolean
}

type FieldRow = { key: string; label: string; from: string }
type FilterRow = { key: string; label: string; op: string; options?: { value: string; label: string }[] }
type SortRow = { field: string; dir: string }
type CalcRow = {
  key: string
  label: string
  kind: 'formula' | 'condition'
  formula?: string
  when?: string
  then?: string
  else?: string
  precision?: number
}
type PaginationCfg = {
  defaultPageSize: number
  pageSizeOptions: number[]
  showJump: boolean
  pageParam: string
  pageSizeParam: string
  pageBase: number
  totalPath: string
}

function parseFields(json: string): FieldRow[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr)
      ? arr.map((x) => ({ key: x.key || '', label: x.label || '', from: x.from || x.key || '' }))
      : []
  } catch { return [] }
}

function parseFilters(json: string): FilterRow[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr)
      ? arr.map((x) => ({
          key: x.key || '',
          label: x.label || '',
          op: x.op || x.type || 'contains',
          options: Array.isArray(x.options)
            ? x.options.map((o: any) => ({
                value: String(o?.value ?? ''),
                label: String(o?.label ?? o?.value ?? ''),
              })).filter((o: any) => o.value)
            : [],
        }))
      : []
  } catch { return [] }
}

function parseOrderBy(json: string): SortRow[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr) ? arr.map((x) => ({ field: x.field || '', dir: x.dir || 'asc' })) : []
  } catch { return [] }
}

function parseCalcs(json: string): CalcRow[] {
  try {
    const arr = JSON.parse(json || '[]')
    return Array.isArray(arr)
      ? arr.map((x) => ({
          key: x.key || '',
          label: x.label || '',
          kind: x.kind === 'condition' ? 'condition' : 'formula',
          formula: x.formula || '',
          when: x.when || '',
          then: x.then ?? '是',
          else: x.else ?? '否',
          precision: x.precision ?? 3,
        }))
      : []
  } catch { return [] }
}

function parsePagination(json: string): PaginationCfg {
  try {
    const o = JSON.parse(json || '{}')
    return {
      defaultPageSize: Number(o.defaultPageSize) || 50,
      pageSizeOptions: Array.isArray(o.pageSizeOptions) && o.pageSizeOptions.length
        ? o.pageSizeOptions.map(Number)
        : [20, 50, 100, 200],
      showJump: o.showJump !== false,
      pageParam: o.pageParam || 'page',
      pageSizeParam: o.pageSizeParam || 'pageSize',
      pageBase: o.pageBase === 0 ? 0 : 1,
      totalPath: o.totalPath || '',
    }
  } catch {
    return { defaultPageSize: 50, pageSizeOptions: [20, 50, 100, 200], showJump: true, pageParam: 'page', pageSizeParam: 'pageSize', pageBase: 1, totalPath: '' }
  }
}

const emptyCalc = (): CalcRow => ({
  key: '',
  label: '',
  kind: 'formula',
  formula: '',
  when: '',
  then: '是',
  else: '否',
  precision: 3,
})

export default function Reports() {
  const [rows, setRows] = useState<Report[]>([])
  const [edit, setEdit] = useState<Report | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [fields, setFields] = useState<FieldRow[]>([])
  const [filters, setFilters] = useState<FilterRow[]>([])
  const [orderBy, setOrderBy] = useState<SortRow[]>([])
  const [calcs, setCalcs] = useState<CalcRow[]>([])
  const [pageCfg, setPageCfg] = useState<PaginationCfg>({ defaultPageSize: 50, pageSizeOptions: [20, 50, 100, 200], showJump: true, pageParam: 'page', pageSizeParam: 'pageSize', pageBase: 1, totalPath: '' })
  const [panel, setPanel] = useState<null | 'filter' | 'calc' | 'page'>(null)
  const [panelReport, setPanelReport] = useState<Report | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [datasets, setDatasets] = useState<any[]>([])

  const dsLabel = (code: string) => {
    const d = datasets.find((x) => x.code === code)
    return d ? `${d.name}（${d.code}）` : code
  }

  async function load() {
    setLoading(true)
    try {
      setRows(await api.get<Report[]>('/api/reports'))
      try { setDatasets(await api.get<any[]>('/api/admin/datasets')) } catch { /* member */ }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load().catch((e) => setError(e.message))
  }, [])

  function openEdit(r?: Report) {
    setError('')
    if (r) {
      setIsNew(false)
      setEdit({ ...r })
      setFields(parseFields(r.fieldsJson))
      setFilters(parseFilters(r.filtersJson))
      setOrderBy(parseOrderBy(r.orderByJson || '[]'))
      setCalcs(parseCalcs(r.calcsJson || '[]'))
      setPageCfg(parsePagination(r.paginationJson || '{}'))
    } else {
      setIsNew(true)
      setEdit({
        code: '',
        name: '',
        description: '',
        rootDataset: datasets[0]?.code || '',
        fieldsJson: '[]',
        filtersJson: '[]',
        orderByJson: '[]',
        calcsJson: '[]',
        paginationJson: '{}',
        enabled: true,
      })
      setFields([{ key: '', label: '', from: '' }])
      setFilters([])
      setOrderBy([])
      setCalcs([])
      setPageCfg({ defaultPageSize: 50, pageSizeOptions: [20, 50, 100, 200], showJump: true, pageParam: 'page', pageSizeParam: 'pageSize', pageBase: 1, totalPath: '' })
    }
  }

  async function openPanel(kind: 'filter' | 'calc' | 'page', r?: Report) {
    const src = r || edit
    if (!src?.code) return
    try {
      // always reload latest config so values echo correctly
      const list = await api.get<Report[]>('/api/reports')
      const fresh = list.find((x) => x.code === src.code) || src
      setPanelReport(fresh)
      setFields(parseFields(fresh.fieldsJson))
      setFilters(parseFilters(fresh.filtersJson))
      setOrderBy(parseOrderBy(fresh.orderByJson || '[]'))
      setCalcs(parseCalcs(fresh.calcsJson || '[]'))
      setPageCfg(parsePagination(fresh.paginationJson || '{}'))
      setPanel(kind)
    } catch {
      setPanelReport(src)
      setFields(parseFields(src.fieldsJson))
      setFilters(parseFilters(src.filtersJson))
      setOrderBy(parseOrderBy(src.orderByJson || '[]'))
      setCalcs(parseCalcs(src.calcsJson || '[]'))
      setPageCfg(parsePagination(src.paginationJson || '{}'))
      setPanel(kind)
    }
  }

  async function savePanel() {
    if (!panelReport) return
    try {
      const payload = {
        ...panelReport,
        fieldsJson: panelReport.fieldsJson,
        filtersJson: JSON.stringify(filters.filter((f) => f.key)),
        orderByJson: JSON.stringify(orderBy.filter((s) => s.field)),
        calcsJson: JSON.stringify(calcs.filter((c) => c.key && c.label)),
        paginationJson: JSON.stringify(pageCfg),
      }
      await api.post('/api/admin/reports', payload)
      setPanel(null)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function save() {
    if (!edit) return
    if (!edit.code?.trim()) {
      setError('请填写报表编码（主键，保存后不可改）')
      return
    }
    if (!edit.name?.trim()) {
      setError('请填写报表名称')
      return
    }
    if (!edit.rootDataset) {
      setError('请选择主数据集')
      return
    }
    if (!fields.some((f) => f.key && f.label)) {
      setError('请至少配置一列展示字段')
      return
    }
    try {
      const payload = {
        ...edit,
        fieldsJson: JSON.stringify(fields.filter((f) => f.key && f.label)),
        filtersJson: JSON.stringify(filters.filter((f) => f.key)),
        orderByJson: JSON.stringify(orderBy.filter((s) => s.field)),
        calcsJson: JSON.stringify(calcs.filter((c) => c.key && c.label)),
        paginationJson: JSON.stringify(pageCfg),
      }
      await api.post('/api/admin/reports', payload)
      setEdit(null)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm(`删除报表「${code}」及其关联配置？`)) return
    await api.del(`/api/admin/reports/${code}`)
    await load()
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="report" size={22} /></div>
          <div>
            <h1>报表</h1>
            <p>定义最终展示哪些列、如何筛选排序与计算</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => openEdit()}>新建报表</button>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      <div className="table-wrap">
        <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
          <thead>
            <tr>
              <th>报表</th>
              <th>主数据集</th>
              <th>展示列</th>
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const fs = parseFields(r.fieldsJson)
              return (
                <tr key={r.code}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{r.name}</div>
                    <div className="muted mono" style={{ fontSize: 12 }}>{r.code}</div>
                  </td>
                  <td>{dsLabel(r.rootDataset)}</td>
                  <td className="muted" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {fs.map((f) => f.label).join('、') || '-'}
                  </td>
                  <td className="muted">{r.description || '-'}</td>
                  <td>
                    <div className="row">
                      <Link className="btn sm" to={`/reports/${r.code}`}>查询</Link>
                      <Link className="btn sm" to={`/relations?report=${r.code}`}>关联</Link>
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
        {rows.length === 0 && !loading && <EmptyState variant="report" title="暂无报表" desc="新建报表，配置主数据集与展示列后即可查询" />}
      </div>

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建报表' : '报表定义'}</h3>
                <p className="sub">展示列、筛选、计算列、排序</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>1 · 基本信息</b>
                  <span>从哪张表开始汇总</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      报表编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 rpt_completion"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>报表名称 <span className="req">*</span></label>
                    <input
                      className="input"
                      placeholder="如 完工报告汇总"
                      value={edit.name}
                      onChange={(e) => setEdit({ ...edit, name: e.target.value })}
                    />
                  </div>
                </div>
                <div className="field">
                  <label>说明</label>
                  <input
                    className="input"
                    placeholder="这张报表汇总了什么"
                    value={edit.description || ''}
                    onChange={(e) => setEdit({ ...edit, description: e.target.value })}
                  />
                </div>
                <div className="field">
                  <label>主数据集 <span className="req">*</span></label>
                  <select
                    className="select"
                    value={edit.rootDataset}
                    onChange={(e) => setEdit({ ...edit, rootDataset: e.target.value })}
                  >
                    <option value="">请选择</option>
                    {datasets.map((d) => (
                      <option key={d.code} value={d.code}>{dsLabel(d.code)}</option>
                    ))}
                  </select>
                  <span className="hint">其它表通过「关联关系」挂到主表上</span>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>2 · 展示列</b>
                  <span>最终表格里有哪些列</span>
                  <button
                    className="btn sm"
                    style={{ marginLeft: 'auto' }}
                    onClick={() => setFields([...fields, { key: '', label: '', from: '' }])}
                  >
                    <Icon name="plus" size={12} />加一列
                  </button>
                </div>
                <div className="param-head" style={{ gridTemplateColumns: '1fr 1fr 1.4fr auto' }}>
                  <span>列标识</span>
                  <span>表头名称</span>
                  <span>来源 数据集.字段</span>
                  <span />
                </div>
                {fields.map((f, idx) => (
                  <div className="param-row" key={idx} style={{ gridTemplateColumns: '1fr 1fr 1.4fr auto' }}>
                    <input
                      className="input"
                      placeholder="product_name"
                      value={f.key}
                      onChange={(e) => setFields(fields.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))}
                    />
                    <input
                      className="input"
                      placeholder="产品名称"
                      value={f.label}
                      onChange={(e) => setFields(fields.map((x, i) => (i === idx ? { ...x, label: e.target.value } : x)))}
                    />
                    <input
                      className="input mono"
                      placeholder="ds_wo.product_name"
                      value={f.from}
                      onChange={(e) => setFields(fields.map((x, i) => (i === idx ? { ...x, from: e.target.value } : x)))}
                    />
                    <div className="row" style={{ flexWrap: 'nowrap' }}>
                      <button
                        className="btn sm icon-only"
                        title="上移"
                        disabled={idx === 0}
                        onClick={() => setFields(fields.map((x, i) => (i === idx - 1 ? fields[idx] : i === idx ? fields[idx - 1] : x)))}
                      >
                        <Icon name="chevronUp" size={13} />
                      </button>
                      <button
                        className="btn sm icon-only"
                        title="下移"
                        disabled={idx === fields.length - 1}
                        onClick={() => setFields(fields.map((x, i) => (i === idx ? fields[idx + 1] : i === idx + 1 ? fields[idx] : x)))}
                      >
                        <Icon name="chevronDown" size={13} />
                      </button>
                      <button className="btn sm danger" onClick={() => setFields(fields.filter((_, i) => i !== idx))}>删</button>
                    </div>
                  </div>
                ))}
                {fields.length === 0 && <div className="muted" style={{ fontSize: 12 }}>暂无展示列</div>}
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>3 · 其它配置</b>
                  <span>筛选排序 / 计算列 / 分页可在列表里单独打开</span>
                </div>
                <div className="row">
                  <button className="btn sm" type="button" onClick={() => openPanel('filter', edit)}>筛选与排序</button>
                  <button className="btn sm" type="button" onClick={() => openPanel('calc', edit)}>计算列 / 判断列</button>
                  <button className="btn sm" type="button" onClick={() => openPanel('page', edit)}>分页</button>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setEdit(null)}>取消</button>
              <button className="btn primary" onClick={save}>保存</button>
            </div>
          </div>
        </div>
      )}

      {panel === 'filter' && panelReport && (
        <div className="modal-backdrop" onClick={() => setPanel(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>筛选与排序</h3>
                <p className="sub">{panelReport.name} · 查询页条件与默认排序</p>
              </div>
              <button className="btn sm ghost" onClick={() => setPanel(null)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="form-section">
                <div className="sec-title">
                  <b>筛选条件</b>
                  <span>日期范围 / 下拉 / 文本</span>
                  <button className="btn sm" style={{ marginLeft: 'auto' }}
                    onClick={() => setFilters([...filters, { key: '', label: '', op: 'date_range', options: [] }])}>
                    <Icon name="plus" size={12} />条件
                  </button>
                </div>
                {filters.map((f, idx) => (
                  <div key={idx}>
                    <div className="param-row" style={{ gridTemplateColumns: '1fr 1fr 1fr auto' }}>
                      <input className="input" placeholder="参数名 endImmersionTime"
                        value={f.key}
                        onChange={(e) => setFilters(filters.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))} />
                      <input className="input" placeholder="显示名 完工日期"
                        value={f.label}
                        onChange={(e) => setFilters(filters.map((x, i) => (i === idx ? { ...x, label: e.target.value } : x)))} />
                      <select className="select" value={f.op}
                        onChange={(e) => setFilters(filters.map((x, i) => (i === idx ? { ...x, op: e.target.value } : x)))}>
                        <option value="date_range">日期范围</option>
                        <option value="select">下拉选项</option>
                        <option value="contains">文本包含</option>
                        <option value="eq">精确匹配</option>
                      </select>
                      <button className="btn sm danger"
                        onClick={() => setFilters(filters.filter((_, i) => i !== idx))}>删</button>
                    </div>
                    {f.op === 'select' && (
                      <div className="field" style={{ marginTop: 8, marginBottom: 10 }}>
                        <label>选项（每行一条：值 = 显示名）</label>
                        <textarea className="textarea" rows={3}
                          value={(f.options || []).map((o) => o.value + '=' + o.label).join('\n')}
                          onChange={(e) => {
                            const options = e.target.value.split('\n').map((line) => {
                              const i = line.indexOf('=')
                              if (i < 0) return { value: line.trim(), label: line.trim() }
                              return { value: line.slice(0, i).trim(), label: line.slice(i + 1).trim() }
                            }).filter((o) => o.value)
                            setFilters(filters.map((x, j) => (j === idx ? { ...x, options } : x)))
                          }} />
                      </div>
                    )}
                  </div>
                ))}
              </div>
              <div className="form-section">
                <div className="sec-title">
                  <b>默认排序</b>
                  <button className="btn sm" style={{ marginLeft: 'auto' }}
                    onClick={() => setOrderBy([...orderBy, { field: '', dir: 'desc' }])}><Icon name="plus" size={12} />排序</button>
                </div>
                {orderBy.map((o, idx) => (
                  <div className="param-row" key={idx} style={{ gridTemplateColumns: '1fr 1fr auto' }}>
                    <input className="input" placeholder="列标识 hang_no"
                      value={o.field}
                      onChange={(e) => setOrderBy(orderBy.map((x, i) => (i === idx ? { ...x, field: e.target.value } : x)))} />
                    <select className="select" value={o.dir}
                      onChange={(e) => setOrderBy(orderBy.map((x, i) => (i === idx ? { ...x, dir: e.target.value } : x)))}>
                      <option value="desc">降序</option>
                      <option value="asc">升序</option>
                    </select>
                    <button className="btn sm danger"
                      onClick={() => setOrderBy(orderBy.filter((_, i) => i !== idx))}>删</button>
                  </div>
                ))}
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setPanel(null)}>取消</button>
              <button className="btn primary" onClick={savePanel}>保存</button>
            </div>
          </div>
        </div>
      )}

      {panel === 'calc' && panelReport && (
        <div className="modal-backdrop" onClick={() => setPanel(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>计算列 / 判断列</h3>
                <p className="sub">{panelReport.name} · 变量用展示列的列标识</p>
              </div>
              <button className="btn sm ghost" onClick={() => setPanel(null)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="form-section">
                <div className="sec-title">
                  <b>计算 / 判断</b>
                  <div className="row" style={{ marginLeft: 'auto' }}>
                    <button className="btn sm" onClick={() => setCalcs([...calcs, { ...emptyCalc(), kind: 'formula' }])}><Icon name="plus" size={12} />计算列</button>
                    <button className="btn sm" onClick={() => setCalcs([...calcs, { ...emptyCalc(), kind: 'condition' }])}><Icon name="plus" size={12} />判断列</button>
                  </div>
                </div>
                {calcs.map((c, idx) => (
                  <div key={idx} style={{ marginBottom: 10, padding: 10, border: '1px solid var(--border-soft)', borderRadius: 10 }}>
                    <div className="param-row" style={{ gridTemplateColumns: '90px 1fr 1fr auto' }}>
                      <span className={`badge ${c.kind === 'condition' ? 'warn' : 'ok'}`}>{c.kind === 'condition' ? '判断' : '计算'}</span>
                      <input className="input" placeholder="列标识 zinc_loss" value={c.key}
                        onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))} />
                      <input className="input" placeholder="表头 锌耗" value={c.label}
                        onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, label: e.target.value } : x)))} />
                      <div className="row" style={{ flexWrap: 'nowrap' }}>
                        <button className="btn sm icon-only" title="上移" disabled={idx === 0}
                          onClick={() => setCalcs(calcs.map((x, i) => (i === idx - 1 ? calcs[idx] : i === idx ? calcs[idx - 1] : x)))}><Icon name="chevronUp" size={13} /></button>
                        <button className="btn sm icon-only" title="下移" disabled={idx === calcs.length - 1}
                          onClick={() => setCalcs(calcs.map((x, i) => (i === idx ? calcs[idx + 1] : i === idx + 1 ? calcs[idx] : x)))}><Icon name="chevronDown" size={13} /></button>
                        <button className="btn sm danger" onClick={() => setCalcs(calcs.filter((_, i) => i !== idx))}>删</button>
                      </div>
                    </div>
                    {c.kind === 'formula' ? (
                      <div className="param-row" style={{ gridTemplateColumns: '1fr 100px', marginTop: 8 }}>
                        <input className="input" placeholder="公式 white_weight - black_weight" value={c.formula || ''}
                          onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, formula: e.target.value } : x)))} />
                        <input className="input" type="number" placeholder="小数位" value={c.precision ?? 3}
                          onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, precision: Number(e.target.value) } : x)))} />
                      </div>
                    ) : (
                      <>
                        <div className="field" style={{ marginTop: 8 }}>
                          <input className="input" placeholder="条件 black_weight > white_weight" value={c.when || ''}
                            onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, when: e.target.value } : x)))} />
                        </div>
                        <div className="param-row" style={{ gridTemplateColumns: '1fr 1fr' }}>
                          <input className="input" placeholder="满足时 是" value={c.then ?? '是'}
                            onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, then: e.target.value } : x)))} />
                          <input className="input" placeholder="不满足时 否" value={c.else ?? '否'}
                            onChange={(e) => setCalcs(calcs.map((x, i) => (i === idx ? { ...x, else: e.target.value } : x)))} />
                        </div>
                      </>
                    )}
                  </div>
                ))}
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setPanel(null)}>取消</button>
              <button className="btn primary" onClick={savePanel}>保存</button>
            </div>
          </div>
        </div>
      )}

      {panel === 'page' && panelReport && (
        <div className="modal-backdrop" onClick={() => setPanel(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px, 100%)' }}>
            <div className="modal-header">
              <div>
                <h3>分页配置</h3>
                <p className="sub">{panelReport.name}</p>
              </div>
              <button className="btn sm ghost" onClick={() => setPanel(null)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="form-section">
                <div className="sec-title"><b>分页</b></div>
                <div className="field">
                  <label>默认每页条数</label>
                  <input className="input" type="number" min={1}
                    value={pageCfg.defaultPageSize}
                    onChange={(e) => setPageCfg({ ...pageCfg, defaultPageSize: Number(e.target.value) || 50 })} />
                </div>
                <div className="field">
                  <label>每页可选条数（逗号分隔）</label>
                  <input className="input"
                    value={pageCfg.pageSizeOptions.join(',')}
                    onChange={(e) => setPageCfg({
                      ...pageCfg,
                      pageSizeOptions: e.target.value.split(',').map((x) => Number(x.trim())).filter((n) => n > 0),
                    })} />
                  <span className="hint">例如 20,50,100,200</span>
                </div>
                <div className="field">
                  <label>总数字段路径（用于算总页数）</label>
                  <input className="input" placeholder="如 count 或 data.total"
                    value={pageCfg.totalPath}
                    onChange={(e) => setPageCfg({ ...pageCfg, totalPath: e.target.value })} />
                  <span className="hint">返回 JSON 里总条数在哪一层；留空则用接口上的总数路径</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>页码请求参数名</label>
                    <input className="input" value={pageCfg.pageParam}
                      onChange={(e) => setPageCfg({ ...pageCfg, pageParam: e.target.value })} />
                    <span className="hint">如 page，接口模板写 page 占位</span>
                  </div>
                  <div className="field">
                    <label>每页条数参数名</label>
                    <input className="input" value={pageCfg.pageSizeParam}
                      onChange={(e) => setPageCfg({ ...pageCfg, pageSizeParam: e.target.value })} />
                    <span className="hint">如 limit，接口模板写 pageSize 占位</span>
                  </div>
                </div>
                <div className="field">
                  <label>页码起点</label>
                  <select className="select" value={String(pageCfg.pageBase)}
                    onChange={(e) => setPageCfg({ ...pageCfg, pageBase: Number(e.target.value) })}>
                    <option value="1">从 1 开始</option>
                    <option value="0">从 0 开始</option>
                  </select>
                </div>
                <div className="field">
                  <label>
                    <input type="checkbox" checked={pageCfg.showJump}
                      onChange={(e) => setPageCfg({ ...pageCfg, showJump: e.target.checked })} />
                    {' '}显示跳页
                  </label>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setPanel(null)}>取消</button>
              <button className="btn primary" onClick={savePanel}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
