import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ensureAuthorized, getUser } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Col = {
  key: string
  label: string
  group?: string
  section?: 'base' | 'open' | 'cur' | 'end' | 'tail'
  kind?: 'text' | 'center' | 'ton' | 'money'
  hidden?: boolean
  merge?: boolean
  width?: number
}
type View = {
  month: string
  monthLabel: string
  dataMode?: string
  lastSyncedAt?: string | null
  columns: Col[]
  rows: Record<string, any>[]
  summaryRow: Record<string, any> | null
  total: number
  durationMs: number
}
type Baseline = {
  id?: number
  sortOrder?: number
  rowKind: string
  seqNo?: number | null
  contractNo: string
  u9ProjectCodes: string
  customerName: string
  signDate?: string | null
  salesman: string
  productName: string
  itemCode: string
  unitPrice?: number | null
  estTonnage?: number | null
  estAmount?: number | null
  settleAmount?: number | null
  openBlackStock: number
  openWhiteStock: number
  openWhiteShipCum: number
  openInvoice: number
  openPayment: number
  openBlackInCum: number
  openBlackOutCum: number
  openWhiteInCum: number
  remark: string
}

const MIN_MONTH = '2026-10'
const STICKY = ['seq_no', 'contract_no', 'customer_name']
const STICKY_W: Record<string, number> = { seq_no: 56, contract_no: 150, customer_name: 210 }

function monthKey(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
function monthOptions() {
  const out: string[] = []
  const now = new Date()
  for (let i = 0; i < 36; i++) {
    const k = monthKey(new Date(now.getFullYear(), now.getMonth() - i, 1))
    if (k < MIN_MONTH) break
    out.push(k)
  }
  return out.length ? out : [MIN_MONTH]
}
function fmtSyncTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function fmt(v: any, kind?: string) {
  if (v === null || v === undefined || v === '') return ''
  if (typeof v !== 'number') return String(v)
  if (kind === 'money') return v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  if (kind === 'ton') return v.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
  return String(v)
}

/** merge 列同值连续行合并，且不跨上一个 merge 列（merge_group 隐藏列为第一层边界） */
function mergePlan(rows: any[], cols: Col[]): number[][] {
  const n = rows.length
  const plan = rows.map(() => cols.map(() => 1))
  let parentEnd = rows.map(() => n)
  cols.forEach((c, ci) => {
    if (!c.merge) return
    const end = new Array(n).fill(0)
    let i = 0
    while (i < n) {
      let j = i + 1
      const cur = String(rows[i][c.key] ?? '')
      while (j < parentEnd[i] && String(rows[j][c.key] ?? '') === cur) j++
      plan[i][ci] = j - i
      for (let k = i + 1; k < j; k++) plan[k][ci] = 0
      for (let k = i; k < j; k++) end[k] = j
      i = j
    }
    parentEnd = end
  })
  return plan
}

const emptyBaseline = (): Baseline => ({
  rowKind: 'contract', seqNo: null, contractNo: '', u9ProjectCodes: '', customerName: '',
  signDate: '', salesman: '', productName: '', itemCode: '', unitPrice: null, estTonnage: null,
  estAmount: null, settleAmount: null, openBlackStock: 0, openWhiteStock: 0, openWhiteShipCum: 0,
  openInvoice: 0, openPayment: 0, openBlackInCum: 0, openBlackOutCum: 0, openWhiteInCum: 0, remark: '',
})

const NUM_FIELDS: { key: keyof Baseline; label: string }[] = [
  { key: 'unitPrice', label: '单价（元/吨）' },
  { key: 'estTonnage', label: '预计总吨位' },
  { key: 'estAmount', label: '预计合同金额' },
  { key: 'settleAmount', label: '结算金额' },
  { key: 'openBlackStock', label: '期初黑件库存' },
  { key: 'openWhiteStock', label: '期初白件库存' },
  { key: 'openWhiteShipCum', label: '期初白件累计发货' },
  { key: 'openInvoice', label: '期初开票金额' },
  { key: 'openPayment', label: '期初回款金额' },
  { key: 'openBlackInCum', label: '9月末黑件累计来料' },
  { key: 'openBlackOutCum', label: '9月末黑件累计出库' },
  { key: 'openWhiteInCum', label: '9月末白件累计入库' },
]

function BaselineEditor({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [list, setList] = useState<Baseline[]>([])
  const [kw, setKw] = useState('')
  const [edit, setEdit] = useState<Baseline | null>(null)
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  async function load() {
    try { setList(await api.get<Baseline[]>('/api/contract-scroll/baselines')) } catch (e: any) { setErr(e.message) }
  }
  useEffect(() => { load() }, [])

  const shown = useMemo(() => {
    const k = kw.trim().toLowerCase()
    if (!k) return list
    return list.filter((b) => [b.contractNo, b.customerName, b.productName, b.u9ProjectCodes]
      .some((s) => String(s || '').toLowerCase().includes(k)))
  }, [list, kw])

  async function save() {
    if (!edit) return
    if (!edit.customerName.trim()) { setErr('客户名称不能为空'); return }
    setBusy(true); setErr('')
    try {
      await api.post('/api/contract-scroll/baselines', edit)
      setEdit(null)
      await load()
      onSaved()
    } catch (e: any) { setErr(e.message) } finally { setBusy(false) }
  }
  async function remove(b: Baseline) {
    if (!b.id || !confirm(`删除「${b.contractNo || b.customerName} · ${b.productName}」的期初记录？`)) return
    try { await api.del(`/api/contract-scroll/baselines/${b.id}`); await load(); onSaved() } catch (e: any) { setErr(e.message) }
  }
  const setF = (k: keyof Baseline, v: any) => setEdit((p) => (p ? { ...p, [k]: v } : p))

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal cs-baseline-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>{edit ? (edit.id ? '编辑期初记录' : '新增期初记录') : '2026年10月期初基线'}</h3>
            <p className="sub">只维护10月期初与合同映射；10月起的出入库、开票、回款全部取自U9单据</p>
          </div>
          <button className="btn sm ghost" onClick={edit ? () => setEdit(null) : onClose}>{edit ? '返回列表' : '关闭'}</button>
        </div>
        <div className="modal-body">
          {err && <div className="error-banner">{err}</div>}
          {!edit ? (
            <>
              <div className="row" style={{ marginBottom: 10 }}>
                <input className="input" style={{ flex: 1 }} placeholder="搜索合同号 / 客户 / 产品 / U9项目号"
                  value={kw} onChange={(e) => setKw(e.target.value)} />
                <button className="btn sm primary" onClick={() => setEdit(emptyBaseline())}>
                  <Icon name="plus" size={12} />新增
                </button>
              </div>
              <div className="table-wrap" style={{ maxHeight: '58vh' }}>
                <table>
                  <thead><tr>
                    <th>序号</th><th>合同号</th><th>客户</th><th>产品</th><th>U9项目号/订单</th>
                    <th className="num">期初白件库存</th><th className="num">期初开票</th><th className="num">期初回款</th><th />
                  </tr></thead>
                  <tbody>
                    {shown.map((b) => (
                      <tr key={b.id}>
                        <td>{b.seqNo ?? ''}</td>
                        <td className="mono">{b.contractNo}</td>
                        <td>{b.customerName}</td>
                        <td>{b.productName}</td>
                        <td className="mono td-clip" title={b.u9ProjectCodes}>{b.u9ProjectCodes}</td>
                        <td className="num">{fmt(b.openWhiteStock, 'ton')}</td>
                        <td className="num">{fmt(b.openInvoice, 'money')}</td>
                        <td className="num">{fmt(b.openPayment, 'money')}</td>
                        <td className="td-actions"><div className="row">
                          <button className="btn sm" onClick={() => setEdit({ ...b })}>编辑</button>
                          <button className="btn sm danger" onClick={() => remove(b)}>删除</button>
                        </div></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {shown.length === 0 && <EmptyState compact variant="search" title="没有匹配的期初记录" />}
              </div>
            </>
          ) : (
            <>
              <div className="form-section">
                <div className="sec-title"><b>合同档案</b><span>多料品合同每个料品一条，序号相同即合并显示</span></div>
                <div className="form-grid">
                  <div className="field"><label>行类型</label>
                    <select className="select" value={edit.rowKind} onChange={(e) => setF('rowKind', e.target.value)}>
                      <option value="contract">正式合同</option>
                      <option value="pre2026">2026年以前合同</option>
                      <option value="uncontracted">无合同散户</option>
                    </select>
                  </div>
                  <div className="field"><label>序号</label>
                    <input className="input" type="number" value={edit.seqNo ?? ''}
                      onChange={(e) => setF('seqNo', e.target.value === '' ? null : Number(e.target.value))} />
                  </div>
                  <div className="field"><label>合同号</label>
                    <input className="input mono" value={edit.contractNo} onChange={(e) => setF('contractNo', e.target.value)} />
                  </div>
                  <div className="field"><label>客户名称 <span className="req">*</span></label>
                    <input className="input" value={edit.customerName} onChange={(e) => setF('customerName', e.target.value)} />
                  </div>
                  <div className="field"><label>签订日期</label>
                    <input className="input" type="date" value={edit.signDate || ''} onChange={(e) => setF('signDate', e.target.value)} />
                  </div>
                  <div className="field"><label>签订人</label>
                    <input className="input" value={edit.salesman} onChange={(e) => setF('salesman', e.target.value)} />
                  </div>
                  <div className="field"><label>产品</label>
                    <input className="input" value={edit.productName} onChange={(e) => setF('productName', e.target.value)} />
                  </div>
                  <div className="field"><label>U9白件料号</label>
                    <input className="input mono" placeholder="1104057-0002" value={edit.itemCode} onChange={(e) => setF('itemCode', e.target.value)} />
                  </div>
                </div>
                <div className="field"><label>U9项目号 / 销售订单号</label>
                  <input className="input mono" placeholder="2026AF-DX-110,SO20261023011" value={edit.u9ProjectCodes}
                    onChange={(e) => setF('u9ProjectCodes', e.target.value)} />
                  <span className="hint">逗号分隔。U9里归属这些号码的出入库、应收、回款都会算到这一行</span>
                </div>
              </div>
              <div className="form-section">
                <div className="sec-title"><b>10月期初数值</b><span>吨 / 元</span></div>
                <div className="form-grid cs-num-grid">
                  {NUM_FIELDS.map((f) => (
                    <div className="field" key={f.key}><label>{f.label}</label>
                      <input className="input num" type="number" step="any" value={(edit[f.key] as any) ?? ''}
                        onChange={(e) => setF(f.key, e.target.value === '' ? null : Number(e.target.value))} />
                    </div>
                  ))}
                </div>
                <div className="field"><label>备注</label>
                  <input className="input" value={edit.remark} onChange={(e) => setF('remark', e.target.value)} />
                </div>
              </div>
            </>
          )}
        </div>
        {edit && (
          <div className="modal-footer">
            <button className="btn" onClick={() => setEdit(null)}>取消</button>
            <button className="btn primary" disabled={busy} onClick={save}>{busy ? '保存中…' : '保存'}</button>
          </div>
        )}
      </div>
    </div>
  )
}

export default function ContractScrollReport({ code, name }: { code: string; name?: string }) {
  const isAdmin = getUser()?.role === 'admin'
  const [searchParams, setSearchParams] = useSearchParams()
  const months = useMemo(monthOptions, [])
  const initMonth = searchParams.get('month')
  const [month, setMonth] = useState(initMonth && months.includes(initMonth) ? initMonth : months[0])
  const [contractNo, setContractNo] = useState(searchParams.get('contractNo') || '')
  const [customer, setCustomer] = useState(searchParams.get('customer') || '')
  const [view, setView] = useState<View | null>(null)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const [baselineOpen, setBaselineOpen] = useState(false)

  const filters = (m = month, cno = contractNo, cust = customer) => ({
    month: m,
    contract_no: cno.trim(),
    customer_name: cust.trim(),
  })

  async function run(m = month, cno = contractNo, cust = customer) {
    setLoading(true); setError('')
    const nextParams = new URLSearchParams()
    if (m) nextParams.set('month', m)
    if (cno.trim()) nextParams.set('contractNo', cno.trim())
    if (cust.trim()) nextParams.set('customer', cust.trim())
    setSearchParams(nextParams, { replace: true })
    try {
      setView(await api.post<View>('/api/contract-scroll/query', { filters: filters(m, cno, cust), page: 1, pageSize: 10000 }))
    } catch (e: any) { setError(e.message) } finally { setLoading(false) }
  }
  useEffect(() => { run(month, contractNo, customer) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [month])

  async function exportExcel() {
    setExporting(true); setError('')
    try {
      const token = localStorage.getItem('report_hub_token') || ''
      const res = await fetch(`/api/reports/${code}/export`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ filters: filters() }),
      })
      ensureAuthorized(res)
      if (!res.ok) throw new Error((await res.text()) || '导出失败')
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `销售合同滚动统计报表-${month}.xlsx`
      document.body.appendChild(a); a.click(); a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) { setError(e.message) } finally { setExporting(false) }
  }

  const allCols = view?.columns || []
  const visCols = allCols.filter((c) => !c.hidden)
  const rows = view?.rows || []
  const plan = useMemo(() => mergePlan(rows, allCols), [rows, allCols])

  // 第一层表头：连续同 group 合并
  const topHeads = useMemo(() => {
    const out: { label: string; span: number; section: string; single?: Col }[] = []
    for (const c of visCols) {
      const last = out[out.length - 1]
      if (c.group && last && !last.single && last.label === c.group) last.span++
      else out.push(c.group ? { label: c.group, span: 1, section: c.section || 'base' } : { label: c.label, span: 1, section: c.section || 'base', single: c })
    }
    return out
  }, [visCols])

  const stickyLeft = (key: string) => {
    let left = 0
    for (const k of STICKY) { if (k === key) return left; left += STICKY_W[k] }
    return undefined
  }
  const cellCls = (c: Col) => {
    const cls = [`sec-${c.section || 'base'}`]
    if (c.kind === 'ton' || c.kind === 'money') cls.push('num')
    if (c.kind === 'center' || c.merge) cls.push('center')
    if (STICKY.includes(c.key)) cls.push('sticky-col')
    if (c.key === 'customer_name') cls.push('sticky-last')
    return cls.join(' ')
  }
  const stickyStyle = (c: Col) => {
    const left = stickyLeft(c.key)
    return left === undefined ? undefined : { left, minWidth: STICKY_W[c.key], maxWidth: STICKY_W[c.key] }
  }
  const negative = (c: Col, v: any) => (c.kind === 'ton' || c.kind === 'money') && typeof v === 'number' && v < -0.005

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="report" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>{name || '销售合同滚动统计报表'}</h1>
            <p className="one-line">智能镀锌事业部 · 10月期初为基线，之后每月出入库、开票、回款全部取自U9单据滚动累计</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm" to="/reports"><Icon name="arrowLeft" size={13} />返回列表</Link>
          {isAdmin && (
            <button className="btn sm" onClick={() => setBaselineOpen(true)}><Icon name="config" size={13} />期初基线</button>
          )}
          <button className="btn sm" onClick={exportExcel} disabled={exporting || loading}>
            <Icon name="download" size={13} />{exporting ? '导出中…' : '导出 Excel'}
          </button>
          <button className="btn sm primary" onClick={() => run()} disabled={loading}>
            <Icon name="play" size={13} />{loading ? '查询中…' : '执行查询'}
          </button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="report-filter-bar">
        <div className="report-filters">
          <div className="filter-item"><span className="muted">统计月份</span>
            <select className="select" value={month} onChange={(e) => setMonth(e.target.value)}>
              {months.map((m) => <option key={m} value={m}>{m.replace('-', '年')}月</option>)}
            </select>
          </div>
          <div className="filter-item"><span className="muted">合同号</span>
            <input className="input" placeholder="如 DX-084" value={contractNo}
              onChange={(e) => setContractNo(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()} />
          </div>
          <div className="filter-item"><span className="muted">客户</span>
            <input className="input" style={{ minWidth: 210 }} placeholder="客户名称关键字" value={customer}
              onChange={(e) => setCustomer(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()} />
          </div>
          {(contractNo || customer) && (
            <button
              type="button"
              className="btn sm ghost"
              onClick={() => {
                setContractNo('')
                setCustomer('')
                run(month, '', '')
              }}
            >
              清空筛选
            </button>
          )}
        </div>
        <div className="report-stats">
          {view?.dataMode && (
            <span className="stat-chip">
              <i>来源</i>
              <b>{view.dataMode === 'realtime' ? '实时聚合' : '本地同步库'}</b>
            </span>
          )}
          {view?.dataMode === 'local' && view.lastSyncedAt && (
            <span className="stat-chip"><i>同步于</i><b>{fmtSyncTime(view.lastSyncedAt)}</b></span>
          )}
          <span className="stat-chip"><i>料品行</i><b>{(view?.total || 0).toLocaleString()}</b></span>
          <span className="stat-chip"><i>耗时</i><b>{view?.durationMs ?? 0} ms</b></span>
        </div>
      </div>

      <div className="report-result">
        {loading && (
          <div className="query-loading"><div className="query-loading-bar" /><span>正在汇总U9单据，请稍候…</span></div>
        )}
        <div className="table-wrap cs-table-wrap" style={loading && rows.length ? { opacity: 0.45, pointerEvents: 'none' } : undefined}>
          {loading && !rows.length ? (
            <div style={{ padding: 8 }}><TableSkeleton rows={10} cols={10} /></div>
          ) : rows.length > 0 ? (
            <table className="cs-table">
              <thead>
                <tr>
                  {topHeads.map((h, i) => h.single ? (
                    <th key={i} rowSpan={2} className={`grp ${cellCls(h.single)}`} style={stickyStyle(h.single)}>{h.label}</th>
                  ) : (
                    <th key={i} colSpan={h.span} className={`grp sec-${h.section}`}>{h.label}</th>
                  ))}
                </tr>
                <tr>
                  {visCols.filter((c) => c.group).map((c) => (
                    <th key={c.key} className={`sub ${cellCls(c)}`}>{c.label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r, ri) => (
                  <tr key={ri} className={r._row_kind === 'uncontracted' ? 'row-loose' : undefined}>
                    {allCols.map((c, ci) => {
                      if (c.hidden) return null
                      const span = plan[ri]?.[ci] ?? 1
                      if (span === 0) return null
                      const v = r[c.key]
                      return (
                        <td key={c.key} rowSpan={span > 1 ? span : undefined}
                          className={`${cellCls(c)}${negative(c, v) ? ' neg' : ''}`} style={stickyStyle(c)}
                          title={c.key === 'customer_name' || c.key === 'product_name' ? String(v ?? '') : undefined}>
                          {fmt(v, c.kind)}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
              {view?.summaryRow && (
                <tfoot>
                  <tr>
                    {visCols.map((c) => (
                      <td key={c.key} className={`${cellCls(c)}${negative(c, view.summaryRow![c.key]) ? ' neg' : ''}`} style={stickyStyle(c)}>
                        {c.key === 'seq_no' ? '合计' : c.kind === 'ton' || c.kind === 'money' ? fmt(view.summaryRow![c.key], c.kind) : ''}
                      </td>
                    ))}
                  </tr>
                </tfoot>
              )}
            </table>
          ) : (
            !loading && <EmptyState variant="search" title="没有符合条件的合同" desc="换个月份或清空合同号、客户关键字再查" />
          )}
        </div>
      </div>

      {baselineOpen && <BaselineEditor onClose={() => setBaselineOpen(false)} onSaved={run} />}
    </>
  )
}
