import { useEffect, useState } from 'react'
import { api, getUser } from '../api/client'
import Icon from '../components/Icon'

type UserRow = {
  id: string
  username: string
  displayName?: string
  role: string
  createdAt?: string
}

export default function Users() {
  const me = getUser()
  const [rows, setRows] = useState<UserRow[]>([])
  const [error, setError] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [edit, setEdit] = useState<UserRow | null>(null)
  const [form, setForm] = useState({ username: '', password: '', displayName: '', role: 'member' })
  const [pwdForm, setPwdForm] = useState({ password: '' })

  async function load() {
    setRows(await api.get<UserRow[]>('/api/admin/users'))
  }

  useEffect(() => {
    load().catch((e) => setError(e.message))
  }, [])

  async function createUser() {
    try {
      await api.post('/api/admin/users', form)
      setCreateOpen(false)
      setForm({ username: '', password: '', displayName: '', role: 'member' })
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function saveEdit() {
    if (!edit) return
    try {
      await api.post(`/api/admin/users/${edit.id}`, {
        displayName: edit.displayName || '',
        role: edit.role,
        ...(pwdForm.password ? { password: pwdForm.password } : {}),
      })
      setEdit(null)
      setPwdForm({ password: '' })
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(u: UserRow) {
    if (!confirm(`删除用户「${u.username}」？`)) return
    try {
      await api.del(`/api/admin/users/${u.id}`)
      await load()
    } catch (e: any) {
      setError(e.message)
    }
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="users" size={22} /></div>
          <div>
            <h1>用户管理</h1>
            <p>admin 可配置 · member 仅查询</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => setCreateOpen(true)}>新建用户</button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>用户</th>
              <th>角色</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id}>
                <td>
                  <div style={{ fontWeight: 600 }}>{u.displayName || u.username}</div>
                  <div className="muted mono" style={{ fontSize: 12 }}>{u.username}</div>
                </td>
                <td>
                  <span className={`badge ${u.role === 'admin' ? 'ok' : 'muted'}`}>
                    {u.role === 'admin' ? '管理员' : '查询员'}
                  </span>
                </td>
                <td className="muted">{u.createdAt ? new Date(u.createdAt).toLocaleString() : '-'}</td>
                <td>
                  <div className="row">
                    <button className="btn sm" onClick={() => { setEdit({ ...u }); setPwdForm({ password: '' }) }}>
                      编辑
                    </button>
                    <button
                      className="btn sm danger"
                      disabled={u.id === me?.id || u.username === 'admin'}
                      onClick={() => remove(u)}
                    >
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {createOpen && (
        <div className="modal-backdrop" onClick={() => setCreateOpen(false)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>新建用户</h3>
                <p className="sub">分配查询或配置权限</p>
              </div>
              <button className="btn sm ghost" onClick={() => setCreateOpen(false)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="form-section">
                <div className="sec-title"><b>账号信息</b></div>
                <div className="form-grid">
                  <div className="field">
                    <label>用户名 <span className="req">*</span></label>
                    <input className="input" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
                  </div>
                  <div className="field">
                    <label>显示名称</label>
                    <input className="input" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
                  </div>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>初始密码 <span className="req">*</span></label>
                    <input className="input" type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
                    <span className="hint">至少 6 位</span>
                  </div>
                  <div className="field">
                    <label>角色</label>
                    <select className="select" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
                      <option value="member">查询员（member）</option>
                      <option value="admin">管理员（admin）</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setCreateOpen(false)}>取消</button>
              <button className="btn primary" onClick={createUser}>创建</button>
            </div>
          </div>
        </div>
      )}

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>编辑用户</h3>
                <p className="sub">{edit.username}</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>
            <div className="modal-body">
              <div className="form-section">
                <div className="sec-title"><b>资料与权限</b></div>
                <div className="form-grid">
                  <div className="field">
                    <label>显示名称</label>
                    <input
                      className="input"
                      value={edit.displayName || ''}
                      onChange={(e) => setEdit({ ...edit, displayName: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>角色</label>
                    <select
                      className="select"
                      value={edit.role}
                      onChange={(e) => setEdit({ ...edit, role: e.target.value })}
                    >
                      <option value="member">查询员（member）</option>
                      <option value="admin">管理员（admin）</option>
                    </select>
                  </div>
                </div>
                <div className="field">
                  <label>重置密码（留空则不改）</label>
                  <input
                    className="input"
                    type="password"
                    placeholder="至少 6 位"
                    value={pwdForm.password}
                    onChange={(e) => setPwdForm({ password: e.target.value })}
                  />
                </div>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn" onClick={() => setEdit(null)}>取消</button>
              <button className="btn primary" onClick={saveEdit}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
