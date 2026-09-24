import { useEffect, useState } from 'react'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Ep = {
  code: string
  dataSourceCode: string
  name: string
  method: string
  path: string
  queryTemplate: string
  bodyTemplate?: string
  bodyType?: 'none' | 'json' | 'form' | string
  listPath: string
  totalPath?: string
  pagination: string
  headersTemplate?: string
}

type QueryRow = { key: string; value: string }

type TestResult = {
  ok: boolean
  url?: string
  durationMs?: number
  rowCount?: number
  total?: number
  rows?: any[]
  raw?: string
  error?: string
}

function parseQueryTemplate(json: string): QueryRow[] {
  try {
    const o = JSON.parse(json || '{}')
    return Object.entries(o).map(([key, value]) => ({
      key,
      value: String(value ?? ''),
    }))
  } catch {
    return []
  }
}

function toQueryTemplate(rows: QueryRow[]) {
  const o: Record<string, string> = {}
  for (const r of rows) {
    if (r.key.trim()) o[r.key.trim()] = r.value
  }
  return JSON.stringify(o)
}

const empty = (): Ep => ({
  code: '',
  dataSourceCode: '',
  name: '',
  method: 'GET',
  path: '',
  queryTemplate: '{}',
  bodyTemplate: '',
  bodyType: 'none',
  listPath: 'data',
  totalPath: 'count',
  pagination: 'page',
  headersTemplate: '{}',
})

const BODY_TYPE_LABEL: Record<string, string> = {
  none: '无 Body',
  form: 'Form',
  json: 'JSON',
}

function detectBodyType(ep: Ep): 'none' | 'json' | 'form' {
  const t = (ep.bodyType || '').toLowerCase()
  if (t === 'none' || t === 'json' || t === 'form') return t
  if (ep.bodyTemplate && ep.bodyTemplate.trim()) return 'json'
  return 'none'
}

export default function Endpoints() {
  const [rows, setRows] = useState<Ep[]>([])
  const [dss, setDss] = useState<any[]>([])
  const [edit, setEdit] = useState<Ep | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [qrows, setQrows] = useState<QueryRow[]>([])
  const [formRows, setFormRows] = useState<QueryRow[]>([])
  const [bodyType, setBodyType] = useState<'none' | 'json' | 'form'>('none')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TestResult | null>(null)
  const [testParams, setTestParams] = useState('')

  const dsLabel = (code: string) => {
    const d = dss.find((x) => x.code === code)
    return d ? `${d.name}（${d.code}）` : code
  }

  async function load() {
    setLoading(true)
    try {
      setRows(await api.get<Ep[]>('/api/admin/endpoints'))
      setDss(await api.get<any[]>('/api/admin/datasources'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load().catch((e) => setError(e.message))
  }, [])

  function openEdit(ep?: Ep) {
    setTestResult(null)
    setTestParams('')
    setError('')
    if (ep) {
      setIsNew(false)
      const bt = detectBodyType(ep)
      setBodyType(bt)
      setEdit({ ...ep, bodyType: bt })
      setQrows(parseQueryTemplate(ep.queryTemplate))
      if (bt === 'form') {
        setFormRows(parseQueryTemplate(ep.bodyTemplate || '{}'))
      } else {
        setFormRows([])
      }
    } else {
      setIsNew(true)
      setBodyType('none')
      setEdit(empty())
      setQrows([
        { key: 'page', value: '{{page}}' },
        { key: 'limit', value: '{{pageSize}}' },
      ])
      setFormRows([])
    }
  }

  function switchBodyType(t: 'none' | 'json' | 'form') {
    setBodyType(t)
    if (!edit) return
    setEdit({ ...edit, bodyType: t, bodyTemplate: t === 'none' ? '' : edit.bodyTemplate })
    if (t === 'form' && formRows.length === 0) {
      setFormRows([{ key: '', value: '' }])
    }
  }

  function buildEndpoint(): Ep {
    if (!edit) return empty()
    let bodyTemplate = edit.bodyTemplate || ''
    if (bodyType === 'none') {
      bodyTemplate = ''
    } else if (bodyType === 'form') {
      bodyTemplate = toQueryTemplate(formRows)
    }
    return { ...edit, queryTemplate: toQueryTemplate(qrows), bodyType, bodyTemplate }
  }

  async function runTest() {
    const ep = buildEndpoint()
    setTesting(true)
    setTestResult(null)
    try {
      let sampleParams: Record<string, string> = {}
      if (testParams.trim()) sampleParams = JSON.parse(testParams)
      const result = await api.post<TestResult>('/api/admin/endpoints/test', {
        endpoint: ep,
        sampleParams,
      })
      setTestResult(result)
    } catch (e: any) {
      setTestResult({ ok: false, error: e.message })
    } finally {
      setTesting(false)
    }
  }

  async function save(andTest = false) {
    if (!edit) return
    if (!edit.code?.trim()) {
      setError('请填写接口编码（主键，保存后不可改）')
      return
    }
    if (!edit.name?.trim()) {
      setError('请填写显示名称')
      return
    }
    if (!edit.dataSourceCode) {
      setError('请选择所属系统')
      return
    }
    if (!edit.path?.trim()) {
      setError('请填写接口路径')
      return
    }
    try {
      await api.post('/api/admin/endpoints', buildEndpoint())
      setError('')
      await load()
      if (andTest) {
        await runTest()
      } else {
        setEdit(null)
      }
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm(`删除接口「${code}」？`)) return
    await api.del(`/api/admin/endpoints/${code}`)
    await load()
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="api" size={22} /></div>
          <div>
            <h1>接口</h1>
            <p>上游系统怎么调：地址、参数、返回列表在哪</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => openEdit()}>新建接口</button>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      <div className="table-wrap">
        <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
          <thead>
            <tr>
              <th>接口</th>
              <th>所属系统</th>
              <th>请求</th>
              <th>列表字段路径</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.code}>
                <td>
                  <div style={{ fontWeight: 600 }}>{r.name}</div>
                  <div className="muted mono" style={{ fontSize: 12 }}>{r.code}</div>
                </td>
                <td>{dsLabel(r.dataSourceCode)}</td>
                <td className="mono">
                  {r.method} {r.path}
                  <div className="row" style={{ gap: 6, marginTop: 4 }}>
                    <span className="badge muted">
                      {BODY_TYPE_LABEL[detectBodyType(r)] || detectBodyType(r)}
                    </span>
                    {r.method !== 'GET' && r.bodyTemplate ? (
                      <span className="muted" style={{ fontSize: 11 }}>{String(r.bodyTemplate).slice(0, 28)}</span>
                    ) : null}
                  </div>
                </td>
                <td className="mono">{r.listPath}</td>
                <td>
                  <div className="row">
                    <button
                      className="btn sm"
                      onClick={() => {
                        openEdit(r)
                        setTimeout(() => {
                          setTestResult(null)
                          runTest()
                        }, 0)
                      }}
                    >
                      测试调用
                    </button>
                    <button className="btn sm" onClick={() => openEdit(r)}>编辑</button>
                    <button className="btn sm danger" onClick={() => remove(r.code)}>删除</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && rows.length === 0 ? <TableSkeleton rows={5} cols={5} /> : null}
        {rows.length === 0 && !loading && <EmptyState variant="config" title="暂无接口" desc="配置 list / detail 接口路径与参数模板" />}
      </div>

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建接口' : '编辑接口'}</h3>
                <p className="sub">编码是主键，保存后不可修改</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>1 · 基本信息</b>
                  <span>这张接口叫什么、属于哪个系统</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      接口编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 completed_hangup_list"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                    <span className="hint">唯一标识，建议英文蛇形命名；保存后不可改</span>
                  </div>
                  <div className="field">
                    <label>显示名称 <span className="req">*</span></label>
                    <input
                      className="input"
                      placeholder="如 完工挂次列表"
                      value={edit.name}
                      onChange={(e) => setEdit({ ...edit, name: e.target.value })}
                    />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>所属系统 <span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.dataSourceCode}
                      onChange={(e) => setEdit({ ...edit, dataSourceCode: e.target.value })}
                    >
                      <option value="">请选择数据源</option>
                      {dss.map((d) => (
                        <option key={d.code} value={d.code}>{dsLabel(d.code)}</option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label>请求方法</label>
                    <select
                      className="select"
                      value={edit.method}
                      onChange={(e) => {
                        const method = e.target.value
                        setEdit({ ...edit, method })
                        if (method === 'GET') {
                          setBodyType('none')
                        } else if (bodyType === 'none') {
                          setBodyType('json')
                        }
                      }}
                    >
                      {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => <option key={m}>{m}</option>)}
                    </select>
                    <span className="hint">
                      {edit.method === 'GET'
                        ? 'GET：配置「查询参数」，拼在 URL 上'
                        : `${edit.method}：配置「请求 Body」；URL 查询参数可选`}
                    </span>
                  </div>
                </div>
                <div className="field">
                  <label>接口路径 <span className="req">*</span></label>
                  <input
                    className="input mono"
                    placeholder="如 PDM_P008/list 或 /api/wo/{{id}}"
                    value={edit.path}
                    onChange={(e) => setEdit({ ...edit, path: e.target.value })}
                  />
                  <span className="hint">相对数据源 Base URL；{"{{id}}"}、{"{{ids}}"} 会在运行时替换</span>
                </div>
              </div>

              {edit.method === 'GET' && (
                <div className="form-section">
                  <div className="sec-title">
                    <b>2 · 查询参数</b>
                    <span>GET 参数拼在 URL 上，值可用占位符</span>
                    <button
                      className="btn sm"
                      style={{ marginLeft: 'auto' }}
                      onClick={() => setQrows([...qrows, { key: '', value: '' }])}
                    >
                      <Icon name="plus" size={12} />加参数
                    </button>
                  </div>
                  <div className="param-head">
                    <span>参数名</span>
                    <span>值 / 占位符</span>
                    <span />
                  </div>
                  {qrows.map((q, idx) => (
                    <div className="param-row" key={idx}>
                      <input
                        className="input"
                        placeholder="如 page"
                        value={q.key}
                        onChange={(e) => setQrows(qrows.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))}
                      />
                      <input
                        className="input"
                        placeholder="如 {{page}} 或 1"
                        value={q.value}
                        onChange={(e) => setQrows(qrows.map((x, i) => (i === idx ? { ...x, value: e.target.value } : x)))}
                      />
                      <button
                        className="btn sm danger"
                        onClick={() => setQrows(qrows.filter((_, i) => i !== idx))}
                      >
                        删
                      </button>
                    </div>
                  ))}
                  {qrows.length === 0 && (
                    <div className="param-empty">
                      <span>暂无 URL 参数，一般 GET 会配 page / limit / 筛选</span>
                      <button type="button" className="btn sm" onClick={() => setQrows([...qrows, { key: '', value: '' }])}>
                        <Icon name="plus" size={12} />加参数
                      </button>
                    </div>
                  )}
                  <div className="field">
                    <span className="hint">
                      常用占位：{"{{page}}"}、{"{{pageSize}}"}、{"{{ids}}"}、{"{{id}}"}、{"{{filter.xxx.start}}"}
                    </span>
                  </div>
                </div>
              )}

              {edit.method !== 'GET' && (
                <div className="form-section">
                  <div className="sec-title">
                    <b>2 · 请求 Body</b>
                    <span>{edit.method} 的请求体，按分类选择编码方式</span>
                  </div>
                  <div className="field">
                    <label>Body 分类</label>
                    <div className="row" style={{ gap: 12, alignItems: 'center' }}>
                      <div className="tabs" role="tablist">
                        <button
                          type="button"
                          className={bodyType === 'none' ? 'active' : ''}
                          onClick={() => switchBodyType('none')}
                        >
                          无 Body
                        </button>
                        <button
                          type="button"
                          className={bodyType === 'form' ? 'active' : ''}
                          onClick={() => switchBodyType('form')}
                        >
                          表单 Form
                        </button>
                        <button
                          type="button"
                          className={bodyType === 'json' ? 'active' : ''}
                          onClick={() => switchBodyType('json')}
                        >
                          JSON
                        </button>
                      </div>
                      <span className="hint" style={{ marginTop: 0 }}>
                        {bodyType === 'none' && '不发送请求体'}
                        {bodyType === 'form' && 'x-www-form-urlencoded'}
                        {bodyType === 'json' && 'application/json'}
                      </span>
                    </div>
                  </div>

                  {bodyType === 'form' && (
                    <div className="field">
                      <div className="sec-title" style={{ marginBottom: 8 }}>
                        <b>表单字段</b>
                        <button
                          type="button"
                          className="btn sm"
                          style={{ marginLeft: 'auto' }}
                          onClick={() => setFormRows([...formRows, { key: '', value: '' }])}
                        >
                          <Icon name="plus" size={12} />加字段
                        </button>
                      </div>
                      <div className="param-head">
                        <span>字段名</span>
                        <span>值 / 占位符</span>
                        <span />
                      </div>
                      {formRows.map((q, idx) => (
                        <div className="param-row" key={idx}>
                          <input
                            className="input"
                            placeholder="如 userName"
                            value={q.key}
                            onChange={(e) => setFormRows(formRows.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))}
                          />
                          <input
                            className="input"
                            placeholder="如 {{filter.work_status}} 或 admin"
                            value={q.value}
                            onChange={(e) => setFormRows(formRows.map((x, i) => (i === idx ? { ...x, value: e.target.value } : x)))}
                          />
                          <button
                            type="button"
                            className="btn sm danger"
                            onClick={() => setFormRows(formRows.filter((_, i) => i !== idx))}
                          >
                            删
                          </button>
                        </div>
                      ))}
                      {formRows.length === 0 && (
                        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>暂无表单字段</div>
                      )}
                    </div>
                  )}

                  {bodyType === 'json' && (
                    <div className="field">
                      <label>Body JSON 模板</label>
                      <textarea
                        className="textarea"
                        rows={6}
                        placeholder={'[{"DocNo":"{{id}}"}]'}
                        value={edit.bodyTemplate || ''}
                        onChange={(e) => setEdit({ ...edit, bodyTemplate: e.target.value })}
                      />
                      <span className="hint">
                        例：U9C 完工报告 Body 为 DocNo 数组；测试附加参数填 id = GC-xxx
                      </span>
                    </div>
                  )}
                </div>
              )}

              {edit.method !== 'GET' && (
                <div className="form-section">
                  <div className="sec-title">
                    <b>2c · URL 查询参数</b>
                    <span>可选 · POST 也可拼在 URL 上</span>
                    <button
                      className="btn sm"
                      style={{ marginLeft: 'auto' }}
                      onClick={() => setQrows([...qrows, { key: '', value: '' }])}
                    >
                      <Icon name="plus" size={12} />加参数
                    </button>
                  </div>
                  {qrows.map((q, idx) => (
                    <div className="param-row" key={idx}>
                      <input
                        className="input"
                        placeholder="如 token"
                        value={q.key}
                        onChange={(e) => setQrows(qrows.map((x, i) => (i === idx ? { ...x, key: e.target.value } : x)))}
                      />
                      <input
                        className="input"
                        placeholder="值 / 占位符"
                        value={q.value}
                        onChange={(e) => setQrows(qrows.map((x, i) => (i === idx ? { ...x, value: e.target.value } : x)))}
                      />
                      <button
                        className="btn sm danger"
                        onClick={() => setQrows(qrows.filter((_, i) => i !== idx))}
                      >
                        删
                      </button>
                    </div>
                  ))}
                  {qrows.length === 0 && (
                    <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>一般不需要；确有 Query 再加</div>
                  )}
                </div>
              )}

              <div className="form-section">
                <div className="sec-title">
                  <b>3 · 返回结构</b>
                  <span>告诉引擎行数组和总数在 JSON 哪一层</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>列表字段路径 <span className="req">*</span></label>
                    <input
                      className="input mono"
                      placeholder="如 data 或 data.records"
                      value={edit.listPath}
                      onChange={(e) => setEdit({ ...edit, listPath: e.target.value })}
                    />
                    <span className="hint">行数组路径。返回若是 {`{data:[…],count:1}`} 就填 data</span>
                  </div>
                  <div className="field">
                    <label>总数字段路径</label>
                    <input
                      className="input mono"
                      placeholder="如 count 或 data.total"
                      value={edit.totalPath || ''}
                      onChange={(e) => setEdit({ ...edit, totalPath: e.target.value })}
                    />
                    <span className="hint">可选，用于分页总数</span>
                  </div>
                </div>
              </div>

              <div className="form-section test-panel">
                <div className="sec-title">
                  <b>4 · 测试调用</b>
                  <span>用当前配置真实请求一次，看能否解析出行</span>
                  <button
                    className="btn primary sm"
                    style={{ marginLeft: 'auto' }}
                    disabled={testing}
                    onClick={runTest}
                  >
                    {testing ? '调用中…' : '发送测试请求'}
                  </button>
                </div>
                <div className="field">
                  <label>附加测试参数（可选 JSON）</label>
                  <input
                    className="input mono"
                    placeholder='{"id":"CR001","ids":"CR001,CR002"}'
                    value={testParams}
                    onChange={(e) => setTestParams(e.target.value)}
                  />
                </div>

                {testResult && (
                  <div className="stack" style={{ gap: 8, marginBottom: 10 }}>
                    <div className="row">
                      <span className={`badge ${testResult.ok ? 'ok' : 'err'}`}>
                        {testResult.ok ? '成功' : '失败'}
                      </span>
                      {testResult.durationMs != null && <span className="muted">{testResult.durationMs} ms</span>}
                      {testResult.rowCount != null && <span className="muted">解析 {testResult.rowCount} 行</span>}
                      {testResult.total != null && <span className="muted">总数 {testResult.total}</span>}
                    </div>
                    {testResult.url && (
                      <div className="mono muted" style={{ fontSize: 11, wordBreak: 'break-all' }}>{testResult.url}</div>
                    )}
                    {testResult.error && (
                      <div className="error-banner" style={{ marginBottom: 0 }}>{testResult.error}</div>
                    )}
                    {testResult.rows && testResult.rows.length > 0 && (
                      <>
                        <div className="muted" style={{ fontSize: 11 }}>解析预览（最多 5 行）</div>
                        <div className="code-block">{JSON.stringify(testResult.rows, null, 2)}</div>
                      </>
                    )}
                    {testResult.raw && (
                      <>
                        <div className="muted" style={{ fontSize: 11 }}>原始响应</div>
                        <div className="code-block">{testResult.raw}</div>
                      </>
                    )}
                  </div>
                )}
              </div>
            </div>

            <div className="modal-footer">
              <button className="btn" onClick={() => setEdit(null)}>取消</button>
              <button className="btn" onClick={() => save(true)}>保存并测试</button>
              <button className="btn primary" onClick={() => save(false)}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
