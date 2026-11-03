import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api, clearSession, getUser } from '../api/client'
import Icon, { IconName } from '../components/Icon'
import Logo from '../components/Logo'

const menuAll: { to: string; label: string; icon: IconName; adminOnly: boolean }[] = [
  { to: '/', label: '首页', icon: 'home', adminOnly: false },
  { to: '/reports', label: '报表', icon: 'report', adminOnly: false },
  { to: '/zn-ledger', label: '锌耗台账', icon: 'report', adminOnly: false },
  { to: '/datasources', label: '数据源', icon: 'datasource', adminOnly: true },
  { to: '/endpoints', label: '接口', icon: 'api', adminOnly: true },
  { to: '/datasets', label: '数据集', icon: 'dataset', adminOnly: true },
  { to: '/relations', label: '关联关系', icon: 'relation', adminOnly: true },
  { to: '/sync', label: '数据同步', icon: 'dataset', adminOnly: true },
  { to: '/users', label: '用户管理', icon: 'users', adminOnly: true },
  { to: '/about', label: '使用说明', icon: 'help', adminOnly: false },
]

export default function Shell() {
  const user = getUser()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'admin'
  const menu = menuAll.filter((item) => isAdmin || !item.adminOnly)
  const [menuOpen, setMenuOpen] = useState(false)
  const [infoOpen, setInfoOpen] = useState(false)
  const [queryMode, setQueryMode] = useState<string>('realtime')
  const [modeBusy, setModeBusy] = useState(false)

  useEffect(() => {
    api.get<any>('/api/settings/query-mode').then((d) => {
      setQueryMode(d?.queryMode || 'realtime')
    }).catch(() => {})
  }, [])

  async function toggleQueryMode() {
    if (modeBusy) return
    setModeBusy(true)
    const next = queryMode === 'local' ? 'realtime' : 'local'
    try {
      const d = await api.post<any>('/api/settings/query-mode', { queryMode: next })
      setQueryMode(d?.queryMode || next)
    } catch (e) {
      // ignore
    } finally {
      setModeBusy(false)
    }
  }
  const menuRef = useRef<HTMLDivElement>(null)
  const displayName = user?.displayName || user?.username || 'U'
  const initials = displayName.slice(0, 2).toUpperCase()
  const roleLabel = user?.role === 'admin' ? '管理员' : '查询员'

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
        setInfoOpen(false)
      }
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <Logo size={30} title="REPORT HUB" subtitle="多源关联报表" />
        </div>
        <nav className="nav">
          {menu.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => (isActive ? 'active' : '')}
              onClick={() => setMenuOpen(false)}
            >
              <span className="ico"><Icon name={item.icon} size={17} /></span>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="topbar-links">
            <span>配置中心</span>
            <span className="muted">·</span>
            <NavLink to="/about">使用说明</NavLink>
          </div>
          <div className="row" ref={menuRef} style={{ position: 'relative', gap: 10 }}>
            <div style={{ position: 'relative' }}>
              <button
                className="pill"
                style={{ cursor: 'pointer', border: '1px solid var(--border)' }}
                onClick={() => {
                  toggleQueryMode()
                  setMenuOpen(false)
                }}
                title={queryMode === 'local' ? '当前：本地数据（点击切回实时聚合）' : '当前：实时聚合（点击切换为本地数据）'}
              >
                <Icon name="bolt" size={13} />
                {queryMode === 'local' ? '本地数据' : '实时聚合'}
                <span className={`toggle${queryMode === 'local' ? '' : ' on'}`} aria-hidden />
              </button>
              {infoOpen && (
                <div
                  className="card"
                  style={{
                    position: 'absolute',
                    right: 0,
                    top: 44,
                    width: 300,
                    zIndex: 40,
                    boxShadow: '0 12px 40px rgba(0,0,0,.35)',
                    fontSize: 12,
                  }}
                >
                  <b>{queryMode === 'local' ? '本地数据' : '实时聚合'}</b>
                  <div className="muted" style={{ marginTop: 8, lineHeight: 1.5 }}>
                    {queryMode === 'local'
                      ? '查询本地同步库中的上游原始数据，不打 ERP / MES 接口。字段映射与关联配置不变。'
                      : '业务数据每次查询实时调用上游 ERP / MES，结果不落库。点开关可切换到本地数据。'}
                  </div>
                  <button className="btn sm" style={{ marginTop: 10 }} onClick={() => navigate('/sync')}>
                    数据同步
                  </button>
                </div>
              )}
            </div>

            <div style={{ position: 'relative' }}>
              <button
                className="topbar-user"
                title="个人中心"
                onClick={() => {
                  setMenuOpen(false)
                  setInfoOpen(false)
                  navigate('/profile')
                }}
              >
                <span className="avatar">{initials}</span>
                <span className="topbar-user-meta">
                  <b>{displayName}</b>
                  <span>{roleLabel}</span>
                </span>
              </button>
              <button
                className="btn sm ghost icon-only topbar-user-more"
                aria-label="账号菜单"
                title="账号菜单"
                onClick={() => {
                  setMenuOpen((v) => !v)
                  setInfoOpen(false)
                }}
              >
                <Icon name="chevronDown" size={12} />
              </button>
              {menuOpen && (
                <div
                  className="card"
                  style={{
                    position: 'absolute',
                    right: 0,
                    top: 48,
                    width: 200,
                    zIndex: 40,
                    padding: 8,
                    boxShadow: '0 12px 40px rgba(0,0,0,.35)',
                  }}
                >
                  <div style={{ padding: '8px 10px 10px', borderBottom: '1px solid var(--border-soft)' }}>
                    <div style={{ fontWeight: 600 }}>{displayName}</div>
                    <div className="muted" style={{ fontSize: 12 }}>{roleLabel}</div>
                  </div>
                  <button
                    className="btn sm ghost"
                    style={{ width: '100%', justifyContent: 'flex-start', marginTop: 6 }}
                    onClick={() => {
                      setMenuOpen(false)
                      navigate('/profile')
                    }}
                  >
                    <Icon name="user" size={14} />
                    个人中心
                  </button>
                  <button
                    className="btn sm ghost"
                    style={{ width: '100%', justifyContent: 'flex-start', color: 'var(--danger)' }}
                    onClick={() => {
                      clearSession()
                      navigate('/login')
                    }}
                  >
                    <Icon name="logout" size={14} />
                    退出登录
                  </button>
                </div>
              )}
            </div>
          </div>
        </header>
        <div className="content">
          <Outlet />
        </div>
      </div>
    </div>
  )
}
