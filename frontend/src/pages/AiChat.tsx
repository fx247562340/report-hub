import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { api, ensureAuthorized, getToken } from '../api/client'
import Icon from '../components/Icon'
import MarkdownView from '../components/MarkdownView'
import { LogoMark } from '../components/Logo'

type AiConfigView = {
  enabled: boolean
  ready: boolean
  baseUrl: string
  apiKey: string
  hasApiKey: boolean
  model: string
  temperature: number
  maxToolRounds: number
  timeoutSeconds: number
}

type AiSession = {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

type ToolAudit = {
  tool: string
  label: string
  summary: string
}

type DrillLink = {
  label: string
  to: string
}

type ChatMsg = {
  id: string | number
  role: 'user' | 'assistant'
  content: string
  tools?: ToolAudit[]
  links?: DrillLink[]
  durationMs?: number | null
  createdAt?: string
  streaming?: boolean
  statusText?: string
  isError?: boolean
}

const QUICK_PROMPTS = [
  { tag: '经营总览', text: '汇总一下本月的白件发货、开票、回款和期末总欠款，并说明环比变化' },
  { tag: '欠款排查', text: '列出本月期末总欠款前 5 的客户，拆分已开票未回款和已发货未开票' },
  { tag: '负库存预警', text: '排查当前有哪些合同出现黑件或白件负库存，按严重程度列出来' },
  { tag: '跨月对比', text: '对比 2026-10 和本月的经营表现，找出欠款变动最大的客户' },
  { tag: '重量超差', text: '查询本月挂次白件重量差异不一致的异常记录，列出差异最大的几挂' },
  { tag: '运维巡检', text: '检查目前 13 张本地同步表的同步状态，有没有同步失败的表？' },
]

const PROVIDER_PRESETS = [
  { name: '智谱 BigModel', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-plus' },
  { name: '阿里云百炼', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { name: 'OpenAI 兼容', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
]

function fmtSessionTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export default function AiChat() {
  const [config, setConfig] = useState<AiConfigView | null>(null)
  const [sessions, setSessions] = useState<AiSession[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string>('')
  const [messages, setMessages] = useState<ChatMsg[]>([])
  const [searchKw, setSearchKw] = useState('')
  const [input, setInput] = useState('')

  const [loadingMsgs, setLoadingMsgs] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [copiedId, setCopiedId] = useState<string | number | null>(null)
  const [pageError, setPageError] = useState('')

  // 模型配置弹窗状态
  const [cfgOpen, setCfgOpen] = useState(false)
  const [cfgDraft, setCfgDraft] = useState<AiConfigView | null>(null)
  const [cfgSaving, setCfgSaving] = useState(false)
  const [cfgTesting, setCfgTesting] = useState(false)
  const [cfgMsg, setCfgMsg] = useState('')
  const [cfgErr, setCfgErr] = useState('')

  const abortRef = useRef<AbortController | null>(null)
  const endRef = useRef<HTMLDivElement | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)

  async function initLoad() {
    try {
      const [c, list] = await Promise.all([
        api.get<AiConfigView>('/api/ai/config'),
        api.get<AiSession[]>('/api/ai/sessions'),
      ])
      setConfig(c)
      setSessions(list || [])
      if (list && list.length > 0) {
        selectSession(list[0].id)
      }
    } catch (e: any) {
      setPageError(e?.message || '初始化智能问数失败')
    }
  }

  useEffect(() => {
    initLoad()
    return () => { abortRef.current?.abort() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages])

  async function refreshSessions() {
    try {
      const list = await api.get<AiSession[]>('/api/ai/sessions')
      setSessions(list || [])
    } catch { /* ignore */ }
  }

  async function selectSession(id: string) {
    if (streaming) abortRef.current?.abort()
    setActiveSessionId(id)
    setLoadingMsgs(true)
    setPageError('')
    try {
      const list = await api.get<ChatMsg[]>(`/api/ai/sessions/${encodeURIComponent(id)}/messages`)
      setMessages(list || [])
    } catch (e: any) {
      setPageError(e?.message || '加载历史问询失败')
    } finally {
      setLoadingMsgs(false)
    }
  }

  function startNewChat() {
    if (streaming) abortRef.current?.abort()
    setActiveSessionId('')
    setMessages([])
    setPageError('')
    setTimeout(() => textareaRef.current?.focus(), 30)
  }

  async function deleteSession(id: string, e: React.MouseEvent) {
    e.stopPropagation()
    if (!confirm('确认删除这条问询会话及其全部问答记录？')) return
    try {
      await api.del(`/api/ai/sessions/${encodeURIComponent(id)}`)
      const next = sessions.filter((s) => s.id !== id)
      setSessions(next)
      if (activeSessionId === id) {
        if (next.length > 0) selectSession(next[0].id)
        else startNewChat()
      }
    } catch (err: any) {
      setPageError(err?.message || '删除失败')
    }
  }

  async function sendQuestion(qText?: string) {
    const q = (qText ?? input).trim()
    if (!q || streaming) return
    if (!config?.ready) {
      openConfigModal()
      return
    }

    setPageError('')
    setInput('')
    setStreaming(true)

    const tempUserId = `u-${Date.now()}`
    const tempAsstId = `a-${Date.now() + 1}`
    setMessages((prev) => [
      ...prev,
      { id: tempUserId, role: 'user', content: q, createdAt: new Date().toISOString() },
      {
        id: tempAsstId,
        role: 'assistant',
        content: '',
        tools: [],
        links: [],
        streaming: true,
        statusText: '正在连接模型并理解问题…',
      },
    ])

    const ctrl = new AbortController()
    abortRef.current = ctrl

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      }
      const token = getToken()
      if (token) headers.Authorization = `Bearer ${token}`

      const res = await fetch('/api/ai/chat/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          sessionId: activeSessionId || '',
          question: q,
        }),
        signal: ctrl.signal,
      })
      ensureAuthorized(res)

      if (!res.ok) {
        const errText = await res.text()
        let msg = res.statusText || '请求失败'
        try {
          const parsed = JSON.parse(errText)
          if (parsed?.error) msg = parsed.error
        } catch {
          if (errText) msg = errText
        }
        throw new Error(msg)
      }

      const reader = res.body?.getReader()
      if (!reader) throw new Error('浏览器不支持流式响应读取')

      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      const applyEvent = (eventName: string, dataRaw: string) => {
        let data: any = {}
        try { data = JSON.parse(dataRaw) } catch { data = { content: dataRaw } }

        if (eventName === 'session' && data.sessionId) {
          setActiveSessionId(data.sessionId)
          refreshSessions()
        } else if (eventName === 'status') {
          setMessages((prev) =>
            prev.map((m) => (m.id === tempAsstId ? { ...m, statusText: data.text || '正在处理…' } : m))
          )
        } else if (eventName === 'tools' && Array.isArray(data)) {
          setMessages((prev) =>
            prev.map((m) => (m.id === tempAsstId ? { ...m, tools: data } : m))
          )
        } else if (eventName === 'links' && Array.isArray(data)) {
          setMessages((prev) =>
            prev.map((m) => (m.id === tempAsstId ? { ...m, links: data } : m))
          )
        } else if (eventName === 'delta' && data.content) {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === tempAsstId
                ? { ...m, content: m.content + data.content, statusText: undefined }
                : m
            )
          )
        } else if (eventName === 'error') {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === tempAsstId
                ? {
                    ...m,
                    streaming: false,
                    statusText: undefined,
                    isError: true,
                    content: m.content || (data.message || '模型返回异常'),
                  }
                : m
            )
          )
        } else if (eventName === 'done') {
          setMessages((prev) =>
            prev.map((m) =>
              m.id === tempAsstId
                ? {
                    ...m,
                    id: data.messageId || m.id,
                    streaming: false,
                    statusText: undefined,
                    durationMs: data.durationMs ?? m.durationMs,
                    tools: Array.isArray(data.tools) ? data.tools : m.tools,
                    links: Array.isArray(data.links) ? data.links : m.links,
                  }
                : m
            )
          )
          refreshSessions()
        }
      }

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split('\n\n')
        buffer = frames.pop() || ''
        for (const frame of frames) {
          let evName = 'message'
          const dataLines: string[] = []
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) evName = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
          }
          if (dataLines.length > 0) {
            applyEvent(evName, dataLines.join('\n'))
          }
        }
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === tempAsstId
              ? { ...m, streaming: false, statusText: undefined, content: m.content || '已停止本次生成。' }
              : m
          )
        )
      } else {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === tempAsstId
              ? { ...m, streaming: false, statusText: undefined, isError: true, content: err?.message || '问数请求失败' }
              : m
          )
        )
      }
    } finally {
      setStreaming(false)
      abortRef.current = null
    }
  }

  function openConfigModal() {
    setCfgErr('')
    setCfgMsg('')
    setCfgDraft(
      config
        ? { ...config }
        : {
            enabled: true,
            ready: false,
            baseUrl: '',
            apiKey: '',
            hasApiKey: false,
            model: '',
            temperature: 0.2,
            maxToolRounds: 4,
            timeoutSeconds: 60,
          }
    )
    setCfgOpen(true)
  }

  async function saveConfig() {
    if (!cfgDraft) return
    setCfgSaving(true)
    setCfgErr('')
    setCfgMsg('')
    try {
      const saved = await api.post<AiConfigView>('/api/ai/config', cfgDraft)
      setConfig(saved)
      setCfgDraft({ ...saved })
      setCfgMsg('模型配置已保存并立即生效')
    } catch (e: any) {
      setCfgErr(e?.message || '保存失败')
    } finally {
      setCfgSaving(false)
    }
  }

  async function testConfig() {
    if (!cfgDraft) return
    setCfgTesting(true)
    setCfgErr('')
    setCfgMsg('')
    try {
      const res = await api.post<any>('/api/ai/config/test', cfgDraft)
      setCfgMsg(`连接成功 · 模型 ${res.model} · 延迟 ${res.latencyMs} ms`)
    } catch (e: any) {
      setCfgErr(e?.message || '连接测试失败')
    } finally {
      setCfgTesting(false)
    }
  }

  async function copyContent(id: string | number, text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopiedId(id)
      setTimeout(() => setCopiedId((cur) => (cur === id ? null : cur)), 1600)
    } catch { /* ignore */ }
  }

  const filteredSessions = useMemo(() => {
    const kw = searchKw.trim().toLowerCase()
    if (!kw) return sessions
    return sessions.filter((s) => (s.title || '').toLowerCase().includes(kw))
  }, [sessions, searchKw])

  const activeSession = useMemo(
    () => sessions.find((s) => s.id === activeSessionId) || null,
    [sessions, activeSessionId]
  )

  return (
    <div className="ai-shell">
      {/* 左侧：持久化问询历史记录栏 */}
      <aside className="ai-sidebar">
        <div className="ai-sidebar-top">
          <button type="button" className="btn sm primary" style={{ width: '100%' }} onClick={startNewChat}>
            <Icon name="plus" size={14} />新建问询
          </button>
          <div className="ai-side-search">
            <Icon name="search" size={13} />
            <input
              className="input"
              placeholder="搜索历史问询…"
              value={searchKw}
              onChange={(e) => setSearchKw(e.target.value)}
            />
          </div>
        </div>

        <div className="ai-session-list">
          <div className="ai-session-group-label">历史问询记录（{filteredSessions.length}）</div>
          {filteredSessions.length === 0 ? (
            <div className="ai-session-empty">
              {sessions.length === 0 ? '暂无历史问询，发起提问后将自动保存' : '没有匹配的会话'}
            </div>
          ) : (
            filteredSessions.map((s) => (
              <div
                key={s.id}
                role="button"
                tabIndex={0}
                className={`ai-session-item${s.id === activeSessionId ? ' active' : ''}`}
                onClick={() => selectSession(s.id)}
                onKeyDown={(e) => { if (e.key === 'Enter') selectSession(s.id) }}
              >
                <div className="ai-session-main">
                  <div className="ai-session-title" title={s.title}>{s.title}</div>
                  <div className="ai-session-time mono">{fmtSessionTime(s.updatedAt)}</div>
                </div>
                <button
                  type="button"
                  className="ai-session-del"
                  title="删除该会话"
                  aria-label="删除会话"
                  onClick={(e) => deleteSession(s.id, e)}
                >
                  <Icon name="trash" size={13} />
                </button>
              </div>
            ))
          )}
        </div>
      </aside>

      {/* 右侧：主对话区 */}
      <section className="ai-main">
        <header className="ai-main-head">
          <div className="ai-main-head-left">
            <div className="icon-tile sm"><Icon name="sparkle" size={16} /></div>
            <div style={{ minWidth: 0 }}>
              <h1 className="ai-main-title">{activeSession ? activeSession.title : '智能问数助手'}</h1>
              <div className="ai-main-sub">自然语言查询 U9 / MES 本地经营数据 · 结论与内置报表 100% 同源</div>
            </div>
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button
              type="button"
              className={`ai-model-chip${config?.ready ? ' ready' : ' unconfigured'}`}
              onClick={openConfigModal}
              title="点击配置 OpenAI 兼容模型"
            >
              <span className={`ai-model-dot${config?.ready ? ' ok' : ''}`} />
              <span>{config?.ready ? config.model : '未配置模型'}</span>
            </button>
            <button type="button" className="btn sm" onClick={openConfigModal}>
              <Icon name="config" size={13} />模型配置
            </button>
          </div>
        </header>

        {pageError && <div className="error-banner" style={{ margin: '10px 18px 0' }}>{pageError}</div>}

        <div className="ai-messages-scroll">
          {loadingMsgs ? (
            <div className="ai-center-hint">正在加载历史问询记录…</div>
          ) : messages.length === 0 ? (
            <div className="ai-welcome">
              <div className="ai-welcome-mark">
                <LogoMark size={46} />
              </div>
              <h2>用自然语言直接查经营数据</h2>
              <p>
                基于平台内置核算引擎与本地同步库，支持销售合同滚动结存、客户欠款排查、负库存预警、挂次白件重量差异与跨月对比。
              </p>

              {!config?.ready && (
                <div className="ai-setup-callout">
                  <div>
                    <b>尚未配置大模型接入参数</b>
                    <span>支持所有 OpenAI 兼容协议（智谱 BigModel / 阿里云百炼 / DeepSeek / 自定义服务）</span>
                  </div>
                  <button type="button" className="btn sm primary" onClick={openConfigModal}>
                    立即配置模型
                  </button>
                </div>
              )}

              <div className="ai-quick-grid">
                {QUICK_PROMPTS.map((q) => (
                  <button
                    type="button"
                    key={q.tag}
                    className="ai-quick-card"
                    onClick={() => sendQuestion(q.text)}
                  >
                    <span className="ai-quick-tag">{q.tag}</span>
                    <span className="ai-quick-text">{q.text}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="ai-thread">
              {messages.map((m) => (
                <div key={m.id} className={`ai-msg-row ${m.role}`}>
                  <div className={`ai-msg-avatar ${m.role}`}>
                    {m.role === 'user' ? '我' : <Icon name="sparkle" size={14} />}
                  </div>
                  <div className={`ai-msg-bubble ${m.role}${m.isError ? ' error' : ''}`}>
                    {m.role === 'assistant' && m.tools && m.tools.length > 0 && (
                      <div className="ai-tool-chips">
                        {m.tools.map((t, idx) => (
                          <span key={`${t.tool}-${idx}`} className="ai-tool-chip" title={t.summary}>
                            <Icon name="check" size={11} />
                            <b>{t.label}</b>
                            {t.summary && <em>{t.summary}</em>}
                          </span>
                        ))}
                      </div>
                    )}

                    {m.statusText && (
                      <div className="ai-status-line">
                        <span className="dash-load-spinner ai-mini-spin" />
                        <span>{m.statusText}</span>
                      </div>
                    )}

                    {m.content && (
                      m.role === 'user' ? (
                        <div className="ai-user-text">{m.content}</div>
                      ) : (
                        <MarkdownView content={m.content} />
                      )
                    )}

                    {m.role === 'assistant' && !m.streaming && (
                      <div className="ai-msg-foot">
                        <div className="ai-links-row">
                          {(m.links || []).map((l) => (
                            <Link key={l.to} to={l.to} className="ai-drill-link">
                              <Icon name="report" size={12} />
                              <span>{l.label}</span>
                              <Icon name="chevronRight" size={11} />
                            </Link>
                          ))}
                        </div>
                        <div className="ai-msg-meta">
                          {m.durationMs != null && (
                            <span className="muted mono" style={{ fontSize: 11 }}>
                              {(m.durationMs / 1000).toFixed(1)}s
                            </span>
                          )}
                          {m.content && (
                            <button
                              type="button"
                              className="btn sm ghost"
                              onClick={() => copyContent(m.id, m.content)}
                            >
                              <Icon name={copiedId === m.id ? 'check' : 'copy'} size={12} />
                              {copiedId === m.id ? '已复制' : '复制'}
                            </button>
                          )}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={endRef} />
            </div>
          )}
        </div>

        {/* 底部输入框 */}
        <div className="ai-composer-wrap">
          <div className="ai-composer">
            <textarea
              ref={textareaRef}
              rows={2}
              placeholder={
                config?.ready
                  ? '输入业务问题，如“11月欠款最多的客户是谁？”（Enter 发送，Shift+Enter 换行）'
                  : '请先点击右上角「模型配置」填写 OpenAI 兼容 API Key 与模型名…'
              }
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  sendQuestion()
                }
              }}
            />
            <div className="ai-composer-actions">
              <span className="ai-composer-tip">
                只读查询本地同步库与内置核算引擎 · 自动保存问询记录
              </span>
              {streaming ? (
                <button type="button" className="btn sm danger" onClick={() => abortRef.current?.abort()}>
                  停止生成
                </button>
              ) : (
                <button
                  type="button"
                  className="btn sm primary"
                  disabled={!input.trim()}
                  onClick={() => sendQuestion()}
                >
                  <Icon name="send" size={13} />发送
                </button>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* OpenAI 兼容模型配置弹窗（通过 Portal 挂载至 body，确保层级高于顶栏） */}
      {cfgOpen && cfgDraft && createPortal(
        <div className="modal-backdrop" onClick={() => setCfgOpen(false)}>
          <div
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="AI 模型配置"
            style={{ maxWidth: 620 }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <h3>AI 模型配置（OpenAI 兼容协议）</h3>
                <p className="sub">配置保存在数据库，API Key 自动脱敏不回传明文</p>
              </div>
              <button type="button" className="btn sm ghost" onClick={() => setCfgOpen(false)}>关闭</button>
            </div>

            <div className="modal-body">
              {cfgErr && <div className="error-banner">{cfgErr}</div>}
              {cfgMsg && (
                <div className="sync-notice">
                  <Icon name="check" size={14} />
                  <span>{cfgMsg}</span>
                </div>
              )}

              <div className="form-section">
                <div className="sec-title">
                  <b>快捷模板</b>
                  <span>点击一键填入对应平台的 OpenAI 兼容地址</span>
                </div>
                <div className="ai-preset-row">
                  {PROVIDER_PRESETS.map((p) => (
                    <button
                      type="button"
                      key={p.name}
                      className={`sync-preset-btn${cfgDraft.baseUrl === p.baseUrl ? ' active' : ''}`}
                      onClick={() => setCfgDraft({ ...cfgDraft, baseUrl: p.baseUrl, model: p.model, enabled: true })}
                    >
                      {p.name}
                    </button>
                  ))}
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>接口参数</b>
                  <label className="sync-toggle-inline" style={{ marginLeft: 'auto' }}>
                    <span>{cfgDraft.enabled ? '已启用' : '已停用'}</span>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={cfgDraft.enabled}
                      className={`sync-switch${cfgDraft.enabled ? ' on' : ''}`}
                      onClick={() => setCfgDraft({ ...cfgDraft, enabled: !cfgDraft.enabled })}
                    >
                      <span className="sync-switch-thumb" />
                    </button>
                  </label>
                </div>

                <div className="field">
                  <label>API Base URL <span className="req">*</span></label>
                  <input
                    className="input mono"
                    placeholder="https://open.bigmodel.cn/api/paas/v4"
                    value={cfgDraft.baseUrl}
                    onChange={(e) => setCfgDraft({ ...cfgDraft, baseUrl: e.target.value })}
                  />
                  <span className="hint">系统会自动补全 /chat/completions，无需重复填写路径</span>
                </div>

                <div className="form-grid">
                  <div className="field">
                    <label>模型名称（Model）<span className="req">*</span></label>
                    <input
                      className="input mono"
                      placeholder="如 glm-4-plus / qwen-plus / deepseek-chat"
                      value={cfgDraft.model}
                      onChange={(e) => setCfgDraft({ ...cfgDraft, model: e.target.value })}
                    />
                  </div>
                  <div className="field">
                    <label>
                      API Key <span className="req">*</span>
                      {cfgDraft.hasApiKey && <span className="muted">（已保存，留空不覆盖）</span>}
                    </label>
                    <input
                      className="input mono"
                      type="password"
                      autoComplete="new-password"
                      placeholder={cfgDraft.hasApiKey ? '******（保留原密钥）' : 'sk-...'}
                      value={cfgDraft.apiKey === '******' ? '' : cfgDraft.apiKey}
                      onChange={(e) => setCfgDraft({ ...cfgDraft, apiKey: e.target.value })}
                    />
                  </div>
                </div>
              </div>

              <div className="form-section">
                <div className="sec-title">
                  <b>运行参数</b>
                  <span>控制回答发散度与多步查询上限</span>
                </div>
                <div className="form-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
                  <div className="field">
                    <label>Temperature</label>
                    <input
                      className="input mono"
                      type="number"
                      step="0.1"
                      min="0"
                      max="1.5"
                      value={cfgDraft.temperature}
                      onChange={(e) => setCfgDraft({ ...cfgDraft, temperature: Number(e.target.value) })}
                    />
                  </div>
                  <div className="field">
                    <label>最大工具轮次</label>
                    <input
                      className="input mono"
                      type="number"
                      min="1"
                      max="8"
                      value={cfgDraft.maxToolRounds}
                      onChange={(e) => setCfgDraft({ ...cfgDraft, maxToolRounds: Number(e.target.value) || 4 })}
                    />
                  </div>
                  <div className="field">
                    <label>超时时间（秒）</label>
                    <input
                      className="input mono"
                      type="number"
                      min="10"
                      max="180"
                      value={cfgDraft.timeoutSeconds}
                      onChange={(e) => setCfgDraft({ ...cfgDraft, timeoutSeconds: Number(e.target.value) || 60 })}
                    />
                  </div>
                </div>
              </div>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                className="btn"
                style={{ marginRight: 'auto' }}
                disabled={cfgTesting}
                onClick={testConfig}
              >
                {cfgTesting ? '测试中…' : '测试连接'}
              </button>
              <button type="button" className="btn" onClick={() => setCfgOpen(false)}>取消</button>
              <button type="button" className="btn primary" disabled={cfgSaving} onClick={saveConfig}>
                {cfgSaving ? '保存中…' : '保存配置'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
