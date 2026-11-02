import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'

type AddRow = { t: string; w: string }
type Rec = {
  id: number
  work_date: string
  pot_length: number
  pot_width: number
  zinc_density: number
  h0: number
  h1: number
  level_drop_mm: number
  k_kg_per_mm: number
  add_zinc_kg: number
  level_zinc_kg: number
  day_zinc_kg: number
  adds: { t?: string; w?: number }[]
  note: string
}

function todayStr() {
  const t = new Date()
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
}
function nowHM() {
  const t = new Date()
  return `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`
}
function fmt(x: number | null | undefined, d = 0) {
  if (x == null || !isFinite(Number(x))) return '—'
  return Number(x).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d })
}

function calc(L: number, W: number, rho: number, h0: number, h1: number, adds: AddRow[]) {
  const k = L > 0 && W > 0 && rho > 0 ? L * W * rho : NaN
  const add = adds.reduce((s, a) => s + (parseFloat(a.w) || 0), 0)
  const drop = isFinite(h0) && isFinite(h1) ? h1 - h0 : NaN
  const lv = isFinite(drop) && isFinite(k) ? drop * k : NaN
  const tot = isFinite(lv) ? add + lv : NaN
  return { k, add, drop, lv, tot }
}

export default function ZnLedger() {
  const [rows, setRows] = useState<Rec[]>([])
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const [msg, setMsg] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')

  const [form, setForm] = useState({
    workDate: todayStr(),
    potLength: '10',
    potWidth: '2.2',
    zincDensity: '6.55',
    h0: '',
    h1: '',
    note: '',
  })
  const [adds, setAdds] = useState<AddRow[]>([{ t: nowHM(), w: '' }])

  const c = useMemo(() => {
    const n = (s: string) => {
      const v = parseFloat(s)
      return isFinite(v) ? v : NaN
    }
    return calc(n(form.potLength), n(form.potWidth), n(form.zincDensity), n(form.h0), n(form.h1), adds)
  }, [form, adds])

  async function load() {
    setLoading(true)
    setError('')
    try {
      const q = start && end ? `?start=${start}&end=${end}` : ''
      const data = await api.get<Rec[]>(`/api/zn-ledger${q}`)
      setRows(data || [])
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function resetForm() {
    setEditingId(null)
    setForm({ workDate: todayStr(), potLength: form.potLength, potWidth: form.potWidth, zincDensity: form.zincDensity, h0: '', h1: '', note: '' })
    setAdds([{ t: nowHM(), w: '' }])
    setMsg('')
  }

  function editRec(r: Rec) {
    setEditingId(r.id)
    setForm({
      workDate: r.work_date,
      potLength: String(r.pot_length),
      potWidth: String(r.pot_width),
      zincDensity: String(r.zinc_density),
      h0: String(r.h0),
      h1: String(r.h1),
      note: r.note || '',
    })
    setAdds((r.adds?.length ? r.adds : [{}]).map((a) => ({ t: a.t || '', w: a.w == null ? '' : String(a.w) })))
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  async function save() {
    setError('')
    if (!form.workDate) return setMsg('先选日期')
    const payload = {
      id: editingId,
      workDate: form.workDate,
      potLength: Number(form.potLength),
      potWidth: Number(form.potWidth),
      zincDensity: Number(form.zincDensity),
      h0: Number(form.h0),
      h1: Number(form.h1),
      adds: adds
        .map((a) => ({ t: a.t, w: Number(a.w) }))
        .filter((a) => isFinite(a.w) && a.w !== 0),
      note: form.note,
    }
    try {
      await api.post('/api/zn-ledger', payload)
      setMsg(editingId ? `已更新 ${form.workDate}` : `已保存 ${form.workDate}`)
      resetForm()
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(id: number) {
    if (!confirm('删除这条记录？')) return
    try {
      await api.del(`/api/zn-ledger/${id}`)
      if (editingId === id) resetForm()
      await load()
      setMsg('已删除')
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function exportXlsx() {
    setExporting(true)
    setError('')
    try {
      const token = localStorage.getItem('report_hub_token') || ''
      const q = start && end ? `?start=${start}&end=${end}` : ''
      const res = await fetch(`/api/zn-ledger/export${q}`, { headers: { Authorization: `Bearer ${token}` } })
      if (!res.ok) throw new Error('导出失败')
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = '锌耗台账.xlsx'
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      setMsg('已导出 Excel')
    } catch (e: any) {
      setError(e.message)
    } finally {
      setExporting(false)
    }
  }

  const stats = useMemo(() => {
    let tot = 0
    rows.forEach((r) => {
      tot += Number(r.day_zinc_kg) || 0
    })
    const days = rows.length
    return { tot, days, avg: days ? tot / days : 0 }
  }, [rows])

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="report" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>锌耗日结台账</h1>
            <p className="one-line">液位法 · 从上沿往下量 · 当日锌耗 = 加锌 + 液位折算</p>
          </div>
        </div>
        <div className="row">
          <button className="btn sm" onClick={exportXlsx} disabled={exporting || loading}>
            <Icon name="download" size={13} />
            {exporting ? '导出中…' : '导出 Excel'}
          </button>
          <button className="btn sm primary" onClick={load} disabled={loading}>
            <Icon name="play" size={13} />
            {loading ? '加载中…' : '刷新'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {msg && <div className="muted" style={{ marginBottom: 8 }}>{msg}</div>}

      <div className="report-filter-bar">
        <div className="report-filters">
          <div className="filter-item">
            <span className="muted">累计锌耗</span>
            <b>{fmt(stats.tot / 1000, 3)} t</b>
          </div>
          <div className="filter-item">
            <span className="muted">天数</span>
            <b>{stats.days} 天</b>
          </div>
          <div className="filter-item">
            <span className="muted">日均</span>
            <b>{stats.days ? fmt(stats.avg / 1000, 3) : '—'} t</b>
          </div>
          <div className="filter-item">
            <span className="muted">起</span>
            <input className="input" type="date" value={start} onChange={(e) => setStart(e.target.value)} />
          </div>
          <div className="filter-item">
            <span className="muted">止</span>
            <input className="input" type="date" value={end} onChange={(e) => setEnd(e.target.value)} />
          </div>
          <button className="btn sm" onClick={load}>查询</button>
        </div>
      </div>

      <div className="card">
        <h3>{editingId ? '修改记录' : '录入 / 修改'}{editingId ? ` · #${editingId}` : ''}</h3>
        <div className="report-filters" style={{ marginBottom: 8 }}>
          <div className="filter-item"><span className="muted">日期</span>
            <input className="input" type="date" value={form.workDate} onChange={(e) => setForm({ ...form, workDate: e.target.value })} /></div>
          <div className="filter-item"><span className="muted">锅长 m</span>
            <input className="input" type="number" step="0.01" value={form.potLength} onChange={(e) => setForm({ ...form, potLength: e.target.value })} /></div>
          <div className="filter-item"><span className="muted">锅宽 m</span>
            <input className="input" type="number" step="0.01" value={form.potWidth} onChange={(e) => setForm({ ...form, potWidth: e.target.value })} /></div>
          <div className="filter-item"><span className="muted">密度 g/cm³</span>
            <input className="input" type="number" step="0.001" value={form.zincDensity} onChange={(e) => setForm({ ...form, zincDensity: e.target.value })} /></div>
        </div>
        <div className="report-filters" style={{ marginBottom: 8 }}>
          <div className="filter-item"><span className="muted">期初读数 mm</span>
            <input className="input" type="number" step="0.5" value={form.h0} onChange={(e) => setForm({ ...form, h0: e.target.value })} /></div>
          <div className="filter-item"><span className="muted">期末读数 mm</span>
            <input className="input" type="number" step="0.5" value={form.h1} onChange={(e) => setForm({ ...form, h1: e.target.value })} /></div>
          <div className="filter-item"><span className="muted">量程 K</span>
            <b>{isFinite(c.k) ? `${fmt(c.k, 1)} kg/mm` : '—'}</b></div>
          <div className="filter-item"><span className="muted">液面变化</span>
            <b>{isFinite(c.drop) ? `${c.drop >= 0 ? '↓' : '↑'} ${fmt(Math.abs(c.drop), 1)} mm` : '—'}</b></div>
        </div>

        <div className="row" style={{ justifyContent: 'space-between', margin: '8px 0' }}>
          <b>加锌明细 · 合计 {fmt(c.add)} kg</b>
          <button className="btn sm" onClick={() => setAdds([...adds, { t: nowHM(), w: '' }])}>+ 一条</button>
        </div>
        {adds.map((a, i) => (
          <div className="row" key={i} style={{ gap: 8, marginBottom: 6 }}>
            <input className="input" style={{ width: 120 }} type="time" value={a.t}
              onChange={(e) => setAdds(adds.map((x, j) => (j === i ? { ...x, t: e.target.value } : x)))} />
            <input className="input" style={{ width: 140 }} type="number" step="1" placeholder="锌锭净重 kg" value={a.w}
              onChange={(e) => setAdds(adds.map((x, j) => (j === i ? { ...x, w: e.target.value } : x)))} />
            <button className="btn sm" onClick={() => setAdds(adds.filter((_, j) => j !== i))}>−</button>
          </div>
        ))}

        <div className="filter-item" style={{ marginTop: 8 }}>
          <span className="muted">备注</span>
          <input className="input" value={form.note} placeholder="换锅、停线、补加合金锭…"
            onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </div>

        <div style={{
          marginTop: 12, padding: '10px 12px', borderRadius: 8,
          border: '1px solid var(--border)', background: 'var(--bg)',
          fontVariantNumeric: 'tabular-nums',
        }}>
          {isFinite(c.tot) ? (
            <>
              液面{c.drop >= 0 ? '下降' : '上升'} <b>{fmt(Math.abs(c.drop), 1)}</b> mm × {fmt(c.k, 1)} kg/mm = <b>{fmt(c.lv)}</b> kg
              {' '}+ 加锌 <b>{fmt(c.add)}</b> kg
              {' '}→ 当日锌耗 <b style={{ color: 'var(--accent)', fontSize: 18 }}>{fmt(Math.abs(c.tot))}</b> kg
              <span className="muted"> = {fmt(Math.abs(c.tot) / 1000, 3)} t</span>
              {c.tot < 0 && <div className="muted" style={{ color: '#d97706' }}>⚠ 负数：加锌超过液位能解释的增量，请核对读数/加锌</div>}
            </>
          ) : (
            <span className="muted">填期初 / 期末读数后自动计算</span>
          )}
        </div>

        <div className="row" style={{ marginTop: 12, gap: 8 }}>
          <button className="btn sm primary" onClick={save}>{editingId ? '更新记录' : '保存记录'}</button>
          <button className="btn sm" onClick={resetForm}>清空表单</button>
        </div>
      </div>

      <div className="card">
        <h3>记录明细{rows.length ? `（${rows.length} 天）` : ''}</h3>
        <div className="table-wrap">
          {loading && rows.length === 0 ? (
            <div className="muted" style={{ padding: 12 }}>加载中…</div>
          ) : rows.length === 0 ? (
            <EmptyState variant="search" title="还没有记录" desc="填好上面的表单，点「保存记录」" />
          ) : (
            <table>
              <thead>
                <tr>
                  <th>日期</th>
                  <th>期初 mm</th>
                  <th>期末 mm</th>
                  <th>液面变化 mm</th>
                  <th>加锌 kg</th>
                  <th>当日锌耗 kg</th>
                  <th>备注</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>{r.work_date}</td>
                    <td>{fmt(r.h0, 1)}</td>
                    <td>{fmt(r.h1, 1)}</td>
                    <td>{r.level_drop_mm >= 0 ? '↓' : '↑'} {fmt(Math.abs(Number(r.level_drop_mm)), 1)}</td>
                    <td>{fmt(r.add_zinc_kg)}</td>
                    <td style={{ fontWeight: 650, color: 'var(--accent)' }}>
                      {Number(r.day_zinc_kg) < 0 ? '−' : ''}{fmt(Math.abs(Number(r.day_zinc_kg)))}
                    </td>
                    <td className="muted" style={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.note}</td>
                    <td>
                      <button className="btn sm" onClick={() => editRec(r)}>改</button>{' '}
                      <button className="btn sm" onClick={() => remove(r.id)}>删</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="muted" style={{ marginTop: 8, fontSize: 12 }}>
          单位：读数 mm · 锌耗 kg · 数据已落库保存
        </div>
      </div>
    </>
  )
}
