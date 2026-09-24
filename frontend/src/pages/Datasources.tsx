import { useEffect, useState } from 'react'
import { api } from '../api/client'
import Icon from '../components/Icon'
import EmptyState from '../components/EmptyState'
import { TableSkeleton } from '../components/Skeleton'

type Ds = {
  code: string
  name: string
  baseUrl: string
  authType: string
  authConfig: string
  env?: string
  timeoutMs?: number
}

const AUTH_LABEL: Record<string, string> = {
  none: '无需认证',
  basic: 'Basic 账号密码',
  bearer: 'Bearer Token',
  api_key: 'API Key',
  session: '登录会话（Cookie）',
  u9c_oauth: 'U9C OAuth',
  u9c: 'U9C OAuth',
}

type SessionCfg = {
  loginPath: string
  loginMethod: string
  loginContentType: string
  usernameField: string
  passwordField: string
  username: string
  password: string
  timestampField: string
  timestampValue: string
  accessField: string
  accessValue: string
  signField: string
  signAlgo: string
  signTemplate: string
  signSalt: string
  tokenPath: string
  tokenStyle: string
  tokenName: string
  extraCookies: string
  ttlSeconds: number
}

const defaultSession = (): SessionCfg => ({
  loginPath: '/login',
  loginMethod: 'POST',
  loginContentType: 'form',
  usernameField: 'userName',
  passwordField: 'password',
  username: '',
  password: '',
  timestampField: 'timestamp',
  timestampValue: '{{now_ms}}',
  accessField: 'access_token',
  accessValue: '',
  signField: 'sign',
  signAlgo: 'md5',
  signTemplate: '{{userName}}{{password}}{{timestamp}}',
  signSalt: 'RUIMA_SECRET',
  tokenPath: 'data.access_token',
  tokenStyle: 'query',
  tokenName: 'access_token',
  extraCookies: '{}',
  ttlSeconds: 1800,
})

function sessionToAuthConfig(s: SessionCfg) {
  return JSON.stringify({
    loginPath: s.loginPath,
    loginMethod: s.loginMethod,
    loginContentType: s.loginContentType,
    usernameField: s.usernameField,
    passwordField: s.passwordField,
    username: s.username,
    password: s.password,
    fields: {
      [s.timestampField]: s.timestampValue,
      ...(s.accessField ? { [s.accessField]: s.accessValue } : {}),
    },
    sign: s.signTemplate
      ? { targetField: s.signField, algorithm: s.signAlgo, template: s.signTemplate, salt: s.signSalt }
      : {},
    token: s.tokenPath ? { responsePath: s.tokenPath, style: s.tokenStyle, name: s.tokenName } : {},
    extraCookies: JSON.parse(s.extraCookies || '{}'),
    ttlSeconds: Number(s.ttlSeconds) || 1800,
    reloginOnStatus: [401, 403],
  })
}

function authConfigToSession(json: string): SessionCfg {
  const s = defaultSession()
  try {
    const o = JSON.parse(json || '{}')
    s.loginPath = o.loginPath || s.loginPath
    s.loginMethod = o.loginMethod || s.loginMethod
    s.loginContentType = o.loginContentType || s.loginContentType
    s.usernameField = o.usernameField || s.usernameField
    s.passwordField = o.passwordField || s.passwordField
    s.username = o.username || ''
    s.password = o.password || ''
    const fields = o.fields || {}
    s.timestampField = Object.keys(fields)[0] || s.timestampField
    s.timestampValue = String(fields[s.timestampField] ?? s.timestampValue)
    const accessKeys = Object.keys(fields).filter((k) => k !== s.timestampField)
    if (accessKeys.length) {
      s.accessField = accessKeys[0]
      s.accessValue = String(fields[accessKeys[0]] ?? '')
    }
    const sign = o.sign || {}
    s.signField = sign.targetField || s.signField
    s.signAlgo = sign.algorithm || s.signAlgo
    s.signTemplate = sign.template || ''
    s.signSalt = sign.salt || ''
    const token = o.token || {}
    s.tokenPath = token.responsePath || ''
    s.tokenStyle = token.style || s.tokenStyle
    s.tokenName = token.name || s.tokenName
    s.extraCookies = JSON.stringify(o.extraCookies || {})
    s.ttlSeconds = Number(o.ttlSeconds) || 1800
  } catch { /* keep default */ }
  return s
}

const empty = (): Ds => ({
  code: '',
  name: '',
  baseUrl: '',
  authType: 'none',
  authConfig: '{}',
  env: 'prod',
  timeoutMs: 15000,
})

export default function Datasources() {
  const [rows, setRows] = useState<Ds[]>([])
  const [edit, setEdit] = useState<Ds | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [error, setError] = useState('')
  const [authDraft, setAuthDraft] = useState<Record<string, string>>({})
  const [sess, setSess] = useState<SessionCfg>(defaultSession())
  const [testing, setTesting] = useState(false)
  const [testMsg, setTestMsg] = useState('')
  const [loading, setLoading] = useState(true)

  async function load() {
    setLoading(true)
    try {
      setRows(await api.get<Ds[]>('/api/admin/datasources'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load().catch((e) => setError(e.message))
  }, [])

  function openEdit(ds?: Ds) {
    setError('')
    setTestMsg('')
    if (ds) {
      setIsNew(false)
      setEdit({ ...ds })
      if (ds.authType === 'session') {
        setSess(authConfigToSession(ds.authConfig))
        setAuthDraft({})
      } else {
        setSess(defaultSession())
        try {
          const o = JSON.parse(ds.authConfig || '{}')
          setAuthDraft({
            clientId: o.clientId || o.clientid || '',
            clientSecret: o.clientSecret || o.clientsecret || '',
            entCode: o.entCode || '',
            userCode: o.userCode || '',
            orgCode: o.orgCode || '',
            ttlSeconds: String(o.ttlSeconds ?? 240),
            ...o,
          })
        } catch { setAuthDraft({}) }
      }
    } else {
      setIsNew(true)
      setEdit(empty())
      setAuthDraft({})
      setSess(defaultSession())
    }
  }

  function switchAuth(type: string) {
    if (!edit) return
    setEdit({ ...edit, authType: type })
    if (type === 'basic') setAuthDraft({ username: '', password: '' })
    else if (type === 'bearer') setAuthDraft({ token: '' })
    else if (type === 'api_key') setAuthDraft({ headerName: 'X-API-Key', value: '' })
    else if (type === 'session') setSess(defaultSession())
    else if (type === 'u9c_oauth' || type === 'u9c') {
      setAuthDraft({ clientId: '', clientSecret: '', entCode: '', userCode: '', orgCode: '', ttlSeconds: '240' })
    } else setAuthDraft({})
  }

  function buildAuthConfig() {
    if (!edit) return '{}'
    if (edit.authType === 'session') return sessionToAuthConfig(sess)
    if (edit.authType === 'u9c_oauth' || edit.authType === 'u9c') {
      return JSON.stringify({
        clientId: authDraft.clientId || '',
        clientSecret: authDraft.clientSecret || '',
        entCode: authDraft.entCode || '',
        userCode: authDraft.userCode || '',
        orgCode: authDraft.orgCode || '',
        ttlSeconds: Number(authDraft.ttlSeconds) || 240,
        reloginOnStatus: [401, 504],
      })
    }
    return JSON.stringify(authDraft)
  }

  async function testLogin() {
    if (!edit) return
    setTesting(true)
    setTestMsg('')
    try {
      const payload = { ...edit, authConfig: buildAuthConfig() }
      await api.post('/api/admin/datasources', payload)
      await api.post('/api/admin/datasources/test-login', { code: edit.code })
      setTestMsg(edit.authType === 'session' ? '登录成功，会话可用' : 'OAuth 登录成功，token 可用')
      await load()
    } catch (e: any) {
      setTestMsg('登录失败：' + e.message)
    } finally {
      setTesting(false)
    }
  }

  async function save(andTest = false) {
    if (!edit) return
    if (!edit.code?.trim()) {
      setError('请填写编码（主键，保存后不可改）')
      return
    }
    if (!edit.name?.trim()) {
      setError('请填写系统名称')
      return
    }
    if (!edit.baseUrl?.trim()) {
      setError('请填写服务地址')
      return
    }
    try {
      await api.post('/api/admin/datasources', { ...edit, authConfig: buildAuthConfig() })
      setError('')
      await load()
      if (andTest && edit.authType === 'session') {
        await testLogin()
      } else if (!andTest) {
        setEdit(null)
      }
    } catch (e: any) {
      setError(e.message)
    }
  }

  async function remove(code: string) {
    if (!confirm(`删除数据源「${code}」？`)) return
    await api.del(`/api/admin/datasources/${code}`)
    await load()
  }

  return (
    <>
      <div className="page-head">
        <div className="row" style={{ gap: 14 }}>
          <div className="icon-tile"><Icon name="datasource" size={22} /></div>
          <div>
            <h1>数据源</h1>
            <p>接入 ERP / MES 等业务系统的连接与登录方式</p>
          </div>
        </div>
        <button className="btn primary" onClick={() => openEdit()}>新建数据源</button>
      </div>

      {error && !edit && <div className="error-banner">{error}</div>}

      <div className="table-wrap">
        <table style={loading && rows.length === 0 ? { display: 'none' } : undefined}>
          <thead>
            <tr>
              <th>系统</th>
              <th>服务地址</th>
              <th>认证方式</th>
              <th>环境</th>
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
                <td className="mono">{r.baseUrl}</td>
                <td><span className="badge muted">{AUTH_LABEL[r.authType] || r.authType}</span></td>
                <td>{r.env || '-'}</td>
                <td>
                  <div className="row">
                    <button className="btn sm" onClick={() => openEdit(r)}>编辑</button>
                    <button className="btn sm danger" onClick={() => remove(r.code)}>删除</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && rows.length === 0 ? <TableSkeleton rows={5} cols={5} /> : null}
        {rows.length === 0 && !loading && <EmptyState variant="data" title="暂无数据源" desc="添加 ERP / MES 等上游系统连接" />}
      </div>

      {edit && (
        <div className="modal-backdrop" onClick={() => setEdit(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <div>
                <h3>{isNew ? '新建数据源' : '编辑数据源'}</h3>
                <p className="sub">连接信息 + 认证方式；编码是主键</p>
              </div>
              <button className="btn sm ghost" onClick={() => setEdit(null)}>关闭</button>
            </div>

            <div className="modal-body">
              {error && <div className="error-banner">{error}</div>}

              <div className="form-section">
                <div className="sec-title">
                  <b>1 · 基本信息</b>
                  <span>系统标识与入口地址</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>
                      编码 <span className="req">*</span>
                      {!isNew && <span className="lock">（主键 · 已锁定）</span>}
                    </label>
                    <input
                      className="input pk"
                      placeholder="如 ruima_mes"
                      disabled={!isNew}
                      value={edit.code}
                      onChange={(e) => setEdit({ ...edit, code: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>系统名称 <span className="req">*</span></label>
                    <input
                      className="input"
                      placeholder="如 瑞马MES"
                      value={edit.name}
                      onChange={(e) => setEdit({ ...edit, name: e.target.value })}
                    />
                  </div>
                </div>
                <div className="field">
                  <label>服务地址 Base URL <span className="req">*</span></label>
                  <input
                    className="input mono"
                    placeholder="https://bfafdx.com/RUIMA"
                    value={edit.baseUrl}
                    onChange={(e) => setEdit({ ...edit, baseUrl: e.target.value })}
                  />
                  <span className="hint">接口路径会拼在这个地址后面</span>
                </div>
                <div className="form-grid">
                  <div className="field">
                    <label>认证方式</label>
                    <select className="select" value={edit.authType} onChange={(e) => switchAuth(e.target.value)}>
                      {Object.entries(AUTH_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label>环境</label>
                    <select
                      className="select"
                      value={edit.env || 'prod'}
                      onChange={(e) => setEdit({ ...edit, env: e.target.value })}
                    >
                      <option value="demo">演示</option>
                      <option value="dev">开发</option>
                      <option value="test">测试</option>
                      <option value="prod">生产</option>
                    </select>
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>2 · 认证</b>
                  <span>
                    {edit.authType === 'session'
                      ? '先登录拿会话，业务接口自动带 Cookie / Token'
                      : edit.authType === 'u9c_oauth' || edit.authType === 'u9c'
                        ? 'OAuth2 AuthLogin 取 token，业务请求自动带 token 头'
                        : '每次请求固定携带的凭证'}
                  </span>
                </div>

                {edit.authType === 'none' && (
                  <p className="muted" style={{ fontSize: 13, margin: 0 }}>该系统不需要额外认证头。</p>
                )}

                {edit.authType === 'basic' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>用户名</label>
                      <input
                        className="input"
                        value={authDraft.username || ''}
                        onChange={(e) => setAuthDraft({ ...authDraft, username: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>密码</label>
                      <input
                        className="input"
                        type="password"
                        value={authDraft.password || ''}
                        onChange={(e) => setAuthDraft({ ...authDraft, password: e.target.value })}
                      />
                    </div>
                  </div>
                )}

                {edit.authType === 'bearer' && (
                  <div className="field">
                    <label>Token</label>
                    <input
                      className="input"
                      value={authDraft.token || ''}
                      onChange={(e) => setAuthDraft({ ...authDraft, token: e.target.value })}
                    />
                  </div>
                )}

                {edit.authType === 'api_key' && (
                  <div className="form-grid">
                    <div className="field">
                      <label>请求头名称</label>
                      <input
                        className="input"
                        value={authDraft.headerName || 'X-API-Key'}
                        onChange={(e) => setAuthDraft({ ...authDraft, headerName: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label>密钥值</label>
                      <input
                        className="input"
                        value={authDraft.value || ''}
                        onChange={(e) => setAuthDraft({ ...authDraft, value: e.target.value })}
                      />
                    </div>
                  </div>
                )}

                {(edit.authType === 'u9c_oauth' || edit.authType === 'u9c') && (
                  <>
                    <div className="form-grid">
                      <div className="field">
                        <label>Client ID <span className="req">*</span></label>
                        <input
                          className="input mono"
                          placeholder="clientid"
                          value={authDraft.clientId || ''}
                          onChange={(e) => setAuthDraft({ ...authDraft, clientId: e.target.value })}
                        />
                        <span className="hint">OAuth2/AuthLogin 的 clientid</span>
                      </div>
                      <div className="field">
                        <label>Client Secret <span className="req">*</span></label>
                        <input
                          className="input mono"
                          type="password"
                          placeholder="clientsecret"
                          value={authDraft.clientSecret || ''}
                          onChange={(e) => setAuthDraft({ ...authDraft, clientSecret: e.target.value })}
                        />
                        <span className="hint">OAuth2/AuthLogin 的 clientsecret</span>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>企业编码 entCode <span className="req">*</span></label>
                        <input
                          className="input mono"
                          placeholder="如 001"
                          value={authDraft.entCode || ''}
                          onChange={(e) => setAuthDraft({ ...authDraft, entCode: e.target.value })}
                        />
                      </div>
                      <div className="field">
                        <label>组织编码 orgCode <span className="req">*</span></label>
                        <input
                          className="input mono"
                          placeholder="如 0101"
                          value={authDraft.orgCode || ''}
                          onChange={(e) => setAuthDraft({ ...authDraft, orgCode: e.target.value })}
                        />
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>用户编码 userCode <span className="req">*</span></label>
                        <input
                          className="input mono"
                          placeholder="如 af30045"
                          value={authDraft.userCode || ''}
                          onChange={(e) => setAuthDraft({ ...authDraft, userCode: e.target.value })}
                        />
                      </div>
                      <div className="field">
                        <label>Token 有效期（秒）</label>
                        <input
                          className="input"
                          type="number"
                          value={authDraft.ttlSeconds || '240'}
                          onChange={(e) => setAuthDraft({ ...authDraft, ttlSeconds: e.target.value })}
                        />
                        <span className="hint">U9C 官方约 5 分钟，默认 240</span>
                      </div>
                    </div>
                    <p className="muted" style={{ fontSize: 12, margin: '4px 0 0' }}>
                      调用 <span className="mono">GET {"{Base URL}"}/webapi/OAuth2/AuthLogin?clientid&amp;clientsecret&amp;entCode&amp;userCode&amp;orgCode</span>
                      ，成功后业务请求头带 <span className="mono">token: {"{accessToken}"}</span>
                    </p>
                    <div className="row" style={{ margin: '10px 0 8px' }}>
                      <button className="btn" disabled={testing} onClick={testLogin}>
                        {testing ? '测试中…' : '测试登录'}
                      </button>
                      {testMsg && (
                        <span className={testMsg.includes('成功') ? 'badge ok' : 'badge err'}>{testMsg}</span>
                      )}
                    </div>
                  </>
                )}

                {edit.authType === 'session' && (
                  <>
                    <div className="form-grid">
                      <div className="field">
                        <label>登录路径</label>
                        <input
                          className="input mono"
                          value={sess.loginPath}
                          onChange={(e) => setSess({ ...sess, loginPath: e.target.value })}
                        />
                      </div>
                      <div className="field">
                        <label>请求方式 / 格式</label>
                        <div className="row">
                          <select
                            className="select"
                            style={{ flex: 1 }}
                            value={sess.loginMethod}
                            onChange={(e) => setSess({ ...sess, loginMethod: e.target.value })}
                          >
                            <option>POST</option>
                            <option>GET</option>
                          </select>
                          <select
                            className="select"
                            style={{ flex: 1 }}
                            value={sess.loginContentType}
                            onChange={(e) => setSess({ ...sess, loginContentType: e.target.value })}
                          >
                            <option value="form">表单 form</option>
                            <option value="json">JSON</option>
                          </select>
                        </div>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>用户名参数名 / 账号</label>
                        <div className="row">
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.usernameField}
                            onChange={(e) => setSess({ ...sess, usernameField: e.target.value })}
                          />
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.username}
                            onChange={(e) => setSess({ ...sess, username: e.target.value })}
                          />
                        </div>
                      </div>
                      <div className="field">
                        <label>密码参数名 / 密码</label>
                        <div className="row">
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.passwordField}
                            onChange={(e) => setSess({ ...sess, passwordField: e.target.value })}
                          />
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            type="password"
                            value={sess.password}
                            onChange={(e) => setSess({ ...sess, password: e.target.value })}
                          />
                        </div>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>时间戳参数 / 取值</label>
                        <div className="row">
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.timestampField}
                            onChange={(e) => setSess({ ...sess, timestampField: e.target.value })}
                          />
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.timestampValue}
                            onChange={(e) => setSess({ ...sess, timestampValue: e.target.value })}
                          />
                        </div>
                      </div>
                      <div className="field">
                        <label>access_token 参数 / 默认值</label>
                        <div className="row">
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.accessField}
                            onChange={(e) => setSess({ ...sess, accessField: e.target.value })}
                          />
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.accessValue}
                            onChange={(e) => setSess({ ...sess, accessValue: e.target.value })}
                          />
                        </div>
                      </div>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>签名参数 / 算法</label>
                        <div className="row">
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.signField}
                            onChange={(e) => setSess({ ...sess, signField: e.target.value })}
                          />
                          <select
                            className="select"
                            style={{ flex: 1 }}
                            value={sess.signAlgo}
                            onChange={(e) => setSess({ ...sess, signAlgo: e.target.value })}
                          >
                            <option value="md5">MD5</option>
                            <option value="sha1">SHA-1</option>
                            <option value="sha256">SHA-256</option>
                            <option value="none">不哈希</option>
                          </select>
                        </div>
                      </div>
                      <div className="field">
                        <label>签名盐值</label>
                        <input
                          className="input"
                          placeholder="如 RUIMA_SECRET"
                          value={sess.signSalt}
                          onChange={(e) => setSess({ ...sess, signSalt: e.target.value })}
                        />
                      </div>
                    </div>
                    <div className="field">
                      <label>签名模板</label>
                      <input
                        className="input mono"
                        value={sess.signTemplate}
                        onChange={(e) => setSess({ ...sess, signTemplate: e.target.value })}
                      />
                      <span className="hint">
                        RM-MES：{"{{userName}}{{password}}{{timestamp}}"}，结果再拼盐后 MD5
                      </span>
                    </div>
                    <div className="form-grid">
                      <div className="field">
                        <label>登录响应 token 路径</label>
                        <input
                          className="input mono"
                          placeholder="data.access_token"
                          value={sess.tokenPath}
                          onChange={(e) => setSess({ ...sess, tokenPath: e.target.value })}
                        />
                      </div>
                      <div className="field">
                        <label>token 如何带给业务接口</label>
                        <div className="row">
                          <select
                            className="select"
                            style={{ flex: 1 }}
                            value={sess.tokenStyle}
                            onChange={(e) => setSess({ ...sess, tokenStyle: e.target.value })}
                          >
                            <option value="query">查询参数</option>
                            <option value="header">请求头</option>
                            <option value="bearer">Bearer</option>
                            <option value="cookie">Cookie</option>
                          </select>
                          <input
                            className="input"
                            style={{ flex: 1 }}
                            value={sess.tokenName}
                            onChange={(e) => setSess({ ...sess, tokenName: e.target.value })}
                          />
                        </div>
                      </div>
                    </div>
                    <div className="field">
                      <label>附加 Cookie JSON</label>
                      <input
                        className="input mono"
                        placeholder='{"myLineId":"15"}'
                        value={sess.extraCookies}
                        onChange={(e) => setSess({ ...sess, extraCookies: e.target.value })}
                      />
                    </div>

                    <div className="row" style={{ marginBottom: 8 }}>
                      <button className="btn" disabled={testing} onClick={testLogin}>
                        {testing ? '测试中…' : '测试登录'}
                      </button>
                      {testMsg && (
                        <span className={testMsg.includes('成功') ? 'badge ok' : 'badge err'}>{testMsg}</span>
                      )}
                    </div>
                  </>
                )}
              </div>
            </div>

            <div className="modal-footer">
              <button className="btn" onClick={() => setEdit(null)}>取消</button>
              {edit.authType === 'session' && (
                <button className="btn" onClick={() => save(true)}>保存并测试登录</button>
              )}
              <button className="btn primary" onClick={() => save(false)}>保存</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
