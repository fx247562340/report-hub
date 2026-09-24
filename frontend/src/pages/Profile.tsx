import { useState } from 'react'
import { api, getUser, setSession } from '../api/client'
import Icon from '../components/Icon'

export default function Profile() {
  const user = getUser()
  const [displayName, setDisplayName] = useState(user?.displayName || '')
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')
  const [pwd, setPwd] = useState({ oldPassword: '', newPassword: '', confirm: '' })

  async function saveProfile() {
    setErr('')
    setMsg('')
    try {
      const updated = await api.post<any>('/api/auth/profile', { displayName })
      setSession({ ...(user as any), ...updated })
      setMsg('资料已保存')
    } catch (e: any) {
      setErr(e.message)
    }
  }

  async function changePassword() {
    setErr('')
    setMsg('')
    if (pwd.newPassword !== pwd.confirm) {
      setErr('两次输入的新密码不一致')
      return
    }
    try {
      await api.post('/api/auth/change-password', {
        oldPassword: pwd.oldPassword,
        newPassword: pwd.newPassword,
      })
      setPwd({ oldPassword: '', newPassword: '', confirm: '' })
      setMsg('密码已修改')
    } catch (e: any) {
      setErr(e.message)
    }
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="user" size={22} /></div>
          <div>
            <h1>个人中心</h1>
            <p>账号资料与修改密码</p>
          </div>
        </div>
      </div>

      {msg && <div className="badge ok" style={{ marginBottom: 12 }}>{msg}</div>}
      {err && <div className="error-banner">{err}</div>}

      <div className="stack" style={{ maxWidth: 560 }}>
        <div className="card">
          <h3>账号</h3>
          <div className="sub">登录名与角色不可在此修改</div>
          <div className="kv">
            <div className="k">用户名</div>
            <div className="mono">{user?.username}</div>
            <div className="k">角色</div>
            <div>{user?.role === 'admin' ? '管理员' : '查询员'}</div>
          </div>
          <div className="field" style={{ marginTop: 16 }}>
            <label>显示名称</label>
            <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
          </div>
          <button className="btn primary" onClick={saveProfile}>保存资料</button>
        </div>

        <div className="card">
          <h3>修改密码</h3>
          <div className="sub">修改成功后下次登录使用新密码</div>
          <div className="field">
            <label>原密码</label>
            <input className="input" type="password" value={pwd.oldPassword} onChange={(e) => setPwd({ ...pwd, oldPassword: e.target.value })} />
          </div>
          <div className="field">
            <label>新密码</label>
            <input className="input" type="password" value={pwd.newPassword} onChange={(e) => setPwd({ ...pwd, newPassword: e.target.value })} />
          </div>
          <div className="field">
            <label>确认新密码</label>
            <input className="input" type="password" value={pwd.confirm} onChange={(e) => setPwd({ ...pwd, confirm: e.target.value })} />
          </div>
          <button className="btn primary" onClick={changePassword}>修改密码</button>
        </div>
      </div>
    </>
  )
}
