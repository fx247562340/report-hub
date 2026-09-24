import { useEffect, useState } from 'react'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Mapping = {
  sourcePath: string
  targetField: string
  dataType: string
  transform?: string
  sort?: number
}

type Dataset = {
  code: string
  name: string
  endpointCode: string
  fetchHint: string
}

const TYPE_LABEL: Record<string, string> = {
  string: '文本',
  number: '数字',
  date: '日期',
  bool: '布尔',
}

const TRANSFORM_LABEL: Record<string, string> = {
  '': '不处理',
  trim: '去首尾空格',
  upper: '转大写',
  lower: '转小写',
}

const emptyDataset = (): Dataset => ({
  code: '',
  name: '',
  endpointCode: '',
  fetchHint: 'list',
})

const emptyMap = (): Mapping => ({
  sourcePath: '',
  targetField: '',
  dataType: 'string',
  transform: '',
})

export default function Datasets() {
  const [rows, setRows] = useState<Dataset[]>([])
  const [eps, setEps] = useState<any[]>([])
  const [edit, setEdit] = useState<Dataset | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [maps, setMaps] = useState<Mapping[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const epLabel = (code: string) => {
    const e = eps.find((x) => x.code === code)
    return e ? `${e.name}（${e.code}）` : code
  }

  async function load() {
    setLoading(true)
    try {
      setRows(await api.get<Dataset[]>('/api/admin/datasets'))
      setEps(await api.get<any[]>('/api/admin/endpoints'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load().catch((e) => setError(e.message))
  }, [])

  async function openEdit(ds?: Dataset) {
    setError('')
    if (ds) {
      const mappings = await api.get<Mapping[]>(`/api/admin/datasets/${ds.code}/mappings`)
      setIsNew(false)
      setEdit(ds)
      setMaps(mappings.map((m) => ({ ...m, transform: m.transform || '' })))
    } else {
      setIsNew(true)
      setEdit(emptyDataset())
      setMaps([emptyMap()])
    }
  }

  async function save() {
    if (!edit) return
    if (!edit.code?.trim()) {
      setError('请填写数据集编码（主键，保存后不可改）')
      return
    }
    if (!edit.name?.trim()) {
      setError('请填写显示名称')
      return
    }
    if (!edit.endpointCode) {
      setError('请选择绑定接口')
      return
    }
    try {
      await api.post('/api/admin/datasets', {
        dataset: edit,
        mappings: maps.filter((m) => m.sourcePath && m.targetField),
      })
      setEdit(null)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm(`删除数据集「${code}」及其字段映射？`)) return
    await api.del(`/api/admin/datasets/${code}`)
    await load()
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="dataset" size={22} /></div>
          <div>
            <h1>数据集</h1>
            <p>把接口返回的字段，映射成报表里可用的列</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => openEdit()}>新建数据集</button>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      <div className="table-wrap">
        <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
          <thead>
            <tr>
              <th>数据集</th>
              <th>绑定接口</th>
              <th>用途</th>
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
                <td>{epLabel(r.endpointCode)}</td>
                <td>
                  <span className="badge muted">
                    {r.fetchHint === 'list' ? '列表取数' : r.fetchHint === 'detail' ? '单条取数' : r.fetchHint}
                  </span>
                </td>
                <td>
                  <div className="row">
                    <button className="btn sm" onClick={() => openEdit(r)}>字段映射</button>
                    <button className="btn sm danger" onClick={() => remove(r.code)}>删除</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && rows.length === 0 ? <TableSkeleton rows={5} cols={4} /> : null}
        {rows.length === 0 && !loading && <EmptyState variant="data" title="暂无数据集" desc="绑定接口并配置字段映射" />}
      </div>

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建数据集' : '数据集与字段映射'}</h3>
                <p className="sub">接口 + 字段映射 = 逻辑表</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>1 · 基本信息</b>
                  <span>数据集标识与绑定接口</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      数据集编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 ds_hang"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>显示名称 <span className="req">*</span></label>
                    <input
                      className="input"
                      placeholder="如 挂次信息"
                      value={edit.name}
                      onChange={(e) => setEdit({ ...edit, name: e.target.value })}
                    />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>绑定接口 <span className="req">*</span></label>
                    <select
                      className="select"
                      value={edit.endpointCode}
                      onChange={(e) => setEdit({ ...edit, endpointCode: e.target.value })}
                    >
                      <option value="">请选择接口</option>
                      {eps.map((ep) => (
                        <option key={ep.code} value={ep.code}>{epLabel(ep.code)}</option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label>取数用途</label>
                    <select
                      className="select"
                      value={edit.fetchHint}
                      onChange={(e) => setEdit({ ...edit, fetchHint: e.target.value })}
                    >
                      <option value="list">列表取数</option>
                      <option value="detail">单条取数</option>
                    </select>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>2 · 字段映射</b>
                  <span>接口字段 → 报表列名</span>
                  <button
                    className="btn sm"
                    style={{ marginLeft: 'auto' }}
                    onClick={() => setMaps([...maps, emptyMap()])}
                  >
                    <Icon name="plus" size={12} />加一行
                  </button>
                </div>
                <div className="param-head" style={{ gridTemplateColumns: '1.2fr 20px 1.2fr 1fr 1fr auto' }}>
                  <span>接口字段</span>
                  <span />
                  <span>报表列名</span>
                  <span>类型</span>
                  <span>转换</span>
                  <span />
                </div>
                {maps.map((m, idx) => (
                  <div
                    className="param-row"
                    key={idx}
                    style={{ gridTemplateColumns: '1.2fr 20px 1.2fr 1fr 1fr auto' }}
                  >
                    <input
                      className="input"
                      placeholder="orderNumber"
                      value={m.sourcePath}
                      onChange={(e) => setMaps(maps.map((x, i) => (i === idx ? { ...x, sourcePath: e.target.value } : x)))}
                    />
                    <span className="muted">→</span>
                    <input
                      className="input"
                      placeholder="wo_no"
                      value={m.targetField}
                      onChange={(e) => setMaps(maps.map((x, i) => (i === idx ? { ...x, targetField: e.target.value } : x)))}
                    />
                    <select
                      className="select"
                      value={m.dataType}
                      onChange={(e) => setMaps(maps.map((x, i) => (i === idx ? { ...x, dataType: e.target.value } : x)))}
                    >
                      {Object.entries(TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                    <select
                      className="select"
                      value={m.transform || ''}
                      onChange={(e) => setMaps(maps.map((x, i) => (i === idx ? { ...x, transform: e.target.value } : x)))}
                    >
                      {Object.entries(TRANSFORM_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                    <button
                      className="btn sm danger"
                      onClick={() => setMaps(maps.filter((_, i) => i !== idx))}
                    >
                      删
                    </button>
                  </div>
                ))}
                {maps.length === 0 && <div className="muted" style={{ fontSize: 12 }}>暂无映射</div>}
                <div className="field">
                  <span className="hint">报表里的列名建议用英文蛇形，供关联、计算列引用</span>
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
    </>
  )
}
