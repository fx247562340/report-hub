import { FormEvent, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { api, setSession, User } from '../api/client'
import Icon from '../components/Icon'
import { LogoMark } from '../components/Logo'

export default function Login() {
  const nav = useNavigate()
  const loc = useLocation() as any
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('admin123')
  const [showPwd, setShowPwd] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      const user = await api.post<User>('/api/auth/login', { username, password })
      setSession(user)
      nav(loc.state?.from?.pathname || '/', { replace: true })
    } catch (err: any) {
      setError(err.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-stage">
      <div className="login-bg" aria-hidden>
        <div className="login-bg-overlay" />
        <div className="login-visual-copy">
          <div className="login-visual-kicker">REPORT HUB</div>
          <h2>跨系统报表，配置即用</h2>
          <p>连接 ERP / MES 接口，映射字段、关联多源，实时汇总一张表</p>
        </div>
      </div>

      <main className="login-panel">
        <form className="login-card" onSubmit={onSubmit}>
          <div className="login-logo" aria-hidden>
            <LogoMark size={52} />
          </div>
          <div className="login-brand-name">REPORT HUB</div>

          <h1>欢迎回来</h1>
          <p className="login-sub">登录 REPORT HUB，继续配置与查询</p>

          {error && <div className="error-banner">{error}</div>}

          <div className="field">
            <label htmlFor="login-user">用户名</label>
            <div className="login-input">
              <Icon name="user" size={16} />
              <input
                id="login-user"
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
              />
            </div>
          </div>

          <div className="field">
            <label htmlFor="login-pwd">密码</label>
            <div className="login-input">
              <Icon name="lock" size={16} />
              <input
                id="login-pwd"
                type={showPwd ? 'text' : 'password'}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
              />
              <button
                type="button"
                className="login-eye"
                onClick={() => setShowPwd((v) => !v)}
                title={showPwd ? '隐藏密码' : '显示密码'}
                aria-label={showPwd ? '隐藏密码' : '显示密码'}
              >
                {showPwd ? '隐藏' : '显示'}
              </button>
            </div>
          </div>

          <button className="btn primary login-submit" disabled={loading}>
            <span>{loading ? '登录中…' : '登 录'}</span>
            {!loading && <Icon name="arrowLeft" size={16} className="flip-x" />}
          </button>

          <div className="login-divider">
            <span>演示账号</span>
          </div>

          <div className="login-demo">
            <div className="login-demo-item">
              <b>admin</b>
              <span>配置 · admin123</span>
            </div>
            <div className="login-demo-item">
              <b>member</b>
              <span>查询 · member123</span>
            </div>
          </div>
        </form>
      </main>
    </div>
  )
}
