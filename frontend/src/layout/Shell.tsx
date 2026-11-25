import { useEffect, useMemo, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, clearSession, getUser } from '../api/client'
import Icon, { IconName } from '../components/Icon'
import Logo from '../components/Logo'

type NavItem = { to: string; label: string; icon: IconName; adminOnly: boolean; group: string }
type ReportLite = { code: string; name: string; description?: string }

const NAV_GROUPS: { key: string; title: string; adminOnly: boolean; items: NavItem[] }[] = [
  {
    key: 'analysis',
    title: '业务分析',
    adminOnly: false,
    items: [
      { to: '/', label: '经营看板', icon: 'home', adminOnly: false, group: '业务分析' },
      { to: '/reports', label: '报表中心', icon: 'report', adminOnly: false, group: '业务分析' },
    ],
  },
  {
    key: 'integration',
    title: '数据集成',
    adminOnly: true,
    items: [
      { to: '/sync', label: '数据同步', icon: 'sync', adminOnly: true, group: '数据集成' },
      { to: '/datasources', label: '数据源', icon: 'datasource', adminOnly: true, group: '数据集成' },
      { to: '/endpoints', label: '接口管理', icon: 'api', adminOnly: true, group: '数据集成' },
      { to: '/datasets', label: '数据集', icon: 'dataset', adminOnly: true, group: '数据集成' },
      { to: '/relations', label: '关联关系', icon: 'relation', adminOnly: true, group: '数据集成' },
    ],
  },
  {
    key: 'system',
    title: '系统管理',
    adminOnly: true,
    items: [
      { to: '/users', label: '用户管理', icon: 'users', adminOnly: true, group: '系统管理' },
    ],
  },
]

const ROUTE_META: Record<string, { group?: string; title: string }> = {
  '/': { title: '经营看板' },
  '/reports': { group: '业务分析', title: '报表中心' },
  '/sync': { group: '数据集成', title: '数据同步' },
  '/datasources': { group: '数据集成', title: '数据源' },
  '/endpoints': { group: '数据集成', title: '接口管理' },
  '/datasets': { group: '数据集成', title: '数据集' },
  '/relations': { group: '数据集成', title: '关联关系' },
  '/users': { group: '系统管理', title: '用户管理' },
  '/profile': { title: '个人中心' },
  '/about': { title: '使用说明' },
}

const SIDEBAR_KEY = 'report_hub_sidebar_collapsed'
const NAV_GROUPS_KEY = 'report_hub_nav_groups'

function loadGroupOpenState(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(NAV_GROUPS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed && typeof parsed === 'object') {
        return { analysis: true, integration: true, system: true, ...parsed }
      }
    }
  } catch { /* ignore */ }
  return { analysis: true, integration: true, system: true }
}

export default function Shell() {
  const user = getUser()
  const navigate = useNavigate()
  const location = useLocation()
  const isAdmin = user?.role === 'admin'

  const [collapsed, setCollapsed] = useState<boolean>(() => localStorage.getItem(SIDEBAR_KEY) === '1')
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [groupOpen, setGroupOpen] = useState<Record<string, boolean>>(loadGroupOpenState)
  const [menuOpen, setMenuOpen] = useState(false)
  const [cmdOpen, setCmdOpen] = useState(false)
  const [cmdQuery, setCmdQuery] = useState('')
  const [cmdIdx, setCmdIdx] = useState(0)

  const [queryMode, setQueryMode] = useState<string>('local')
  const [modeBusy, setModeBusy] = useState<string>('')
  const [modeError, setModeError] = useState<string>('')
  const [reports, setReports] = useState<ReportLite[]>([])
  const [syncFailed, setSyncFailed] = useState<number>(0)

  const menuRef = useRef<HTMLDivElement>(null)
  const cmdInputRef = useRef<HTMLInputElement>(null)

  const displayName = user?.displayName || user?.username || 'U'
  const initials = displayName.slice(0, 2).toUpperCase()
  const roleLabel = isAdmin ? '管理员' : '查询员'

  const isReportDetail = /^\/reports\/[^/]+/.test(location.pathname)

  useEffect(() => {
    api.get<any>('/api/settings/query-mode').then((d) => {
      setQueryMode(d?.queryMode || 'local')
    }).catch(() => {})
    api.get<ReportLite[]>('/api/reports').then((list) => {
      setReports(list || [])
    }).catch(() => {})
    if (isAdmin) {
      api.get<any[]>('/api/sync/status').then((list) => {
        const failed = (list || []).filter((x) => x.lastJobStatus === 'failed').length
        setSyncFailed(failed)
      }).catch(() => {})
    }
  }, [isAdmin])

  useEffect(() => {
    setDrawerOpen(false)
    // 当前路由命中某个分组时自动展开该分组，避免高亮项被折叠隐藏
    const p = location.pathname
    const matched = NAV_GROUPS.find((g) =>
      g.items.some((it) => (it.to === '/' ? p === '/' : p === it.to || p.startsWith(it.to + '/')))
    )
    if (matched) {
      setGroupOpen((prev) => {
        if (prev[matched.key] !== false) return prev
        const next = { ...prev, [matched.key]: true }
        localStorage.setItem(NAV_GROUPS_KEY, JSON.stringify(next))
        return next
      })
    }
  }, [location.pathname])

  function toggleGroup(key: string) {
    setGroupOpen((prev) => {
      const next = { ...prev, [key]: !(prev[key] !== false) }
      localStorage.setItem(NAV_GROUPS_KEY, JSON.stringify(next))
      return next
    })
  }

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
      }
    }
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setCmdOpen((v) => !v)
        setCmdQuery('')
        setCmdIdx(0)
      } else if (e.key === 'Escape') {
        setCmdOpen(false)
        setMenuOpen(false)
        setDrawerOpen(false)
      }
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [])

  useEffect(() => {
    if (cmdOpen) {
      setTimeout(() => cmdInputRef.current?.focus(), 20)
    }
  }, [cmdOpen])

  function toggleCollapsed() {
    setCollapsed((prev) => {
      const next = !prev
      localStorage.setItem(SIDEBAR_KEY, next ? '1' : '0')
      return next
    })
  }

  async function switchQueryMode(mode: string) {
    if (modeBusy || mode === queryMode) return
    setModeBusy(mode)
    setModeError('')
    try {
      await api.post<any>('/api/settings/query-mode', { queryMode: mode })
      setQueryMode(mode)
      window.location.reload()
    } catch (e: any) {
      setModeError(`切换失败：${e?.message || '请重试'}`)
      setTimeout(() => setModeError(''), 4000)
    } finally {
      setModeBusy('')
    }
  }

  const visibleGroups = useMemo(
    () => NAV_GROUPS.filter((g) => isAdmin || !g.adminOnly),
    [isAdmin]
  )

  const breadcrumb = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/reports/')) {
      const code = decodeURIComponent(p.slice('/reports/'.length))
      const found = reports.find((r) => r.code === code)
      return {
        parent: { label: '报表中心', to: '/reports' },
        current: found?.name || code,
      }
    }
    const meta = ROUTE_META[p]
    if (meta) {
      return {
        parent: meta.group ? { label: meta.group, to: '' } : null,
        current: meta.title,
      }
    }
    return { parent: null, current: '经营看板' }
  }, [location.pathname, reports])

  const cmdItems = useMemo(() => {
    const out: { id: string; label: string; sub: string; to: string; icon: IconName }[] = []
    for (const r of reports) {
      out.push({
        id: `rpt:${r.code}`,
        label: r.name,
        sub: `报表 · ${r.code}`,
        to: `/reports/${r.code}`,
        icon: 'report',
      })
    }
    for (const g of visibleGroups) {
      for (const it of g.items) {
        out.push({
          id: `nav:${it.to}`,
          label: it.label,
          sub: g.title,
          to: it.to,
          icon: it.icon,
        })
      }
    }
    out.push({ id: 'nav:/about', label: '使用说明', sub: '帮助', to: '/about', icon: 'help' })
    out.push({ id: 'nav:/profile', label: '个人中心', sub: '账号', to: '/profile', icon: 'user' })
    const kw = cmdQuery.trim().toLowerCase()
    if (!kw) return out
    return out.filter(
      (x) => x.label.toLowerCase().includes(kw) || x.sub.toLowerCase().includes(kw)
    )
  }, [reports, visibleGroups, cmdQuery])

  useEffect(() => {
    setCmdIdx(0)
  }, [cmdQuery])

  function runCmd(idx: number) {
    const item = cmdItems[idx]
    if (!item) return
    setCmdOpen(false)
    setCmdQuery('')
    navigate(item.to)
  }

  return (
    <div className={`app-shell${collapsed ? ' sidebar-collapsed' : ''}`}>
      {drawerOpen && (
        <div className="sidebar-backdrop" onClick={() => setDrawerOpen(false)} aria-hidden />
      )}
      <aside className={`sidebar${drawerOpen ? ' open' : ''}`}>
        <div className="brand">
          <Logo
            size={30}
            title={collapsed ? '' : 'REPORT HUB'}
            subtitle={collapsed ? '' : '智能经营报表'}
          />
        </div>

        <nav className="nav" aria-label="主导航">
          {visibleGroups.map((group) => {
            const expanded = collapsed ? true : groupOpen[group.key] !== false
            return (
              <div className="nav-group" key={group.key}>
                {!collapsed && (
                  <button
                    type="button"
                    className="nav-group-title clickable"
                    aria-expanded={expanded}
                    onClick={() => toggleGroup(group.key)}
                  >
                    <span className="nav-group-title-left">
                      <span>{group.title}</span>
                      {group.key === 'integration' && !expanded && syncFailed > 0 && (
                        <span className="nav-badge err" title={`${syncFailed} 项同步异常`}>
                          {syncFailed}
                        </span>
                      )}
                    </span>
                    <Icon name={expanded ? 'chevronDown' : 'chevronRight'} size={12} />
                  </button>
                )}

                {expanded && group.items.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    end={item.to === '/'}
                    title={collapsed ? item.label : undefined}
                    className={({ isActive }) => (isActive ? 'active' : '')}
                    onClick={() => setMenuOpen(false)}
                  >
                    <span className="ico"><Icon name={item.icon} size={17} /></span>
                    {!collapsed && <span className="nav-label">{item.label}</span>}
                    {item.to === '/sync' && syncFailed > 0 && (
                      <span className="nav-badge err" title={`${syncFailed} 项同步异常`}>
                        {syncFailed}
                      </span>
                    )}
                  </NavLink>
                ))}

                {/* 业务分析组下方：常用报表一键直达 */}
                {expanded && group.key === 'analysis' && !collapsed && reports.length > 0 && (
                  <div className="nav-sublist">
                    {reports.slice(0, 5).map((r) => (
                      <NavLink
                        key={r.code}
                        to={`/reports/${r.code}`}
                        className={({ isActive }) => `nav-sub-item${isActive ? ' active' : ''}`}
                        title={r.name}
                      >
                        <span className="nav-sub-dot" />
                        <span className="nav-label">{r.name}</span>
                      </NavLink>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </nav>

        <div className="sidebar-foot">
          <NavLink
            to="/about"
            title="使用说明"
            className={({ isActive }) => `sidebar-foot-btn${isActive ? ' active' : ''}`}
          >
            <Icon name="help" size={16} />
            {!collapsed && <span>使用说明</span>}
          </NavLink>
          <button
            type="button"
            className="sidebar-foot-btn sidebar-collapse-btn"
            onClick={toggleCollapsed}
            title={collapsed ? '展开侧边栏' : '收起侧边栏'}
            aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
          >
            <Icon name="sidebar" size={16} />
            {!collapsed && <span>收起</span>}
          </button>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <div className="topbar-left">
            <button
              type="button"
              className="btn sm ghost icon-only mobile-menu-btn"
              aria-label="打开导航菜单"
              onClick={() => setDrawerOpen((v) => !v)}
            >
              <Icon name="menu" size={16} />
            </button>
            <nav className="breadcrumb" aria-label="当前位置">
              {breadcrumb.parent && (
                <>
                  {breadcrumb.parent.to ? (
                    <NavLink to={breadcrumb.parent.to} className="crumb-parent">
                      {breadcrumb.parent.label}
                    </NavLink>
                  ) : (
                    <span className="crumb-parent">{breadcrumb.parent.label}</span>
                  )}
                  <Icon name="chevronRight" size={12} className="crumb-sep" />
                </>
              )}
              <span className="crumb-current">{breadcrumb.current}</span>
            </nav>
          </div>

          <div className="row topbar-right" ref={menuRef} style={{ position: 'relative', gap: 10 }}>
            <button
              type="button"
              className="cmd-trigger"
              onClick={() => {
                setCmdOpen(true)
                setCmdQuery('')
                setCmdIdx(0)
              }}
              title="快速跳转报表或菜单 (⌘K / Ctrl+K)"
            >
              <Icon name="search" size={13} />
              <span className="cmd-trigger-text">跳转报表 / 功能…</span>
              <kbd>⌘K</kbd>
            </button>

            {isAdmin ? (
              <div className="seg" role="group" aria-label="数据模式">
                <button
                  className={`seg-item${queryMode === 'realtime' ? ' active' : ''}`}
                  disabled={modeBusy !== ''}
                  title="每次查询实时调用 U9 / MES 接口，结果不落库"
                  onClick={() => switchQueryMode('realtime')}
                >
                  {modeBusy === 'realtime' ? '切换中…' : '实时聚合'}
                </button>
                <button
                  className={`seg-item${queryMode === 'local' ? ' active' : ''}`}
                  disabled={modeBusy !== ''}
                  title="查询本地同步库中的原始数据，不打上游接口"
                  onClick={() => switchQueryMode('local')}
                >
                  {modeBusy === 'local' ? '切换中…' : '本地数据'}
                </button>
              </div>
            ) : (
              <span className="pill" title="全局查询模式由管理员配置">
                {queryMode === 'realtime' ? '实时聚合' : '本地数据'}
              </span>
            )}
            {modeError && (
              <div className="error-banner" style={{ position: 'absolute', right: 0, top: 44, zIndex: 50, fontSize: 12, padding: '8px 12px' }}>
                {modeError}
              </div>
            )}

            <div style={{ position: 'relative' }}>
              <button
                className="topbar-user"
                title="个人中心"
                onClick={() => {
                  setMenuOpen(false)
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

        <div className={`content${isReportDetail ? ' content-wide' : ''}`}>
          <Outlet />
        </div>
      </div>

      {cmdOpen && (
        <div className="modal-backdrop" onClick={() => setCmdOpen(false)}>
          <div className="cmd-palette" onClick={(e) => e.stopPropagation()} role="dialog" aria-label="快捷跳转">
            <div className="cmd-input-row">
              <Icon name="search" size={16} />
              <input
                ref={cmdInputRef}
                value={cmdQuery}
                onChange={(e) => setCmdQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'ArrowDown') {
                    e.preventDefault()
                    setCmdIdx((i) => Math.min(cmdItems.length - 1, i + 1))
                  } else if (e.key === 'ArrowUp') {
                    e.preventDefault()
                    setCmdIdx((i) => Math.max(0, i - 1))
                  } else if (e.key === 'Enter') {
                    e.preventDefault()
                    runCmd(cmdIdx)
                  }
                }}
                placeholder="输入报表名称或菜单关键词…"
              />
              <kbd>ESC</kbd>
            </div>
            <div className="cmd-list">
              {cmdItems.length === 0 ? (
                <div className="cmd-empty">未找到匹配的报表或功能</div>
              ) : (
                cmdItems.map((it, idx) => (
                  <button
                    type="button"
                    key={it.id}
                    className={`cmd-item${idx === cmdIdx ? ' active' : ''}`}
                    onMouseEnter={() => setCmdIdx(idx)}
                    onClick={() => runCmd(idx)}
                  >
                    <span className="cmd-item-left">
                      <Icon name={it.icon} size={15} />
                      <span>{it.label}</span>
                    </span>
                    <span className="cmd-item-sub">{it.sub}</span>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
