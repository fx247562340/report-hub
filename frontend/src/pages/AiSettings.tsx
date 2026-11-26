import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import Icon from '../components/Icon'
import { TableSkeleton } from '../components/Skeleton'

type AiConfigView = {
  enabled: boolean
  ready: boolean
  baseUrl: string
  apiKey: string
  hasApiKey: boolean
  model: string
  models: string[]
  temperature: number
  maxToolRounds: number
  timeoutSeconds: number
}

type TestResult = {
  model: string
  latencyMs: number
  reply: string
  toolCallingSupported: boolean
  toolCallStatus: 'ok' | 'skipped' | 'error'
  toolCallLatencyMs: number
  toolCallName: string
  toolCallDetail: string
}

function sameModels(a: string[], b: string[]) {
  if (a.length !== b.length) return false
  return a.every((v, i) => v === b[i])
}

export default function AiSettings() {
  const [saved, setSaved] = useState<AiConfigView | null>(null)
  const [draft, setDraft] = useState<AiConfigView | null>(null)
  const [manualModel, setManualModel] = useState('')

  const [upstreamModels, setUpstreamModels] = useState<string[]>([])
  const [upstreamOpen, setUpstreamOpen] = useState(false)
  const [upstreamKw, setUpstreamKw] = useState('')
  const [fetchingModels, setFetchingModels] = useState(false)

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testingModel, setTestingModel] = useState<string | null>(null)
  const [msg, setMsg] = useState('')
  const [warn, setWarn] = useState('')
  const [error, setError] = useState('')
  const [testResult, setTestResult] = useState<TestResult | null>(null)
  const [testedMap, setTestedMap] = useState<Record<string, TestResult>>({})

  const testing = testingModel !== null

  function normalizeView(data: AiConfigView): AiConfigView {
    const list = Array.isArray(data.models) ? data.models.filter(Boolean) : []
    if (data.model && !list.includes(data.model)) list.unshift(data.model)
    return {
      ...data,
      models: list,
      model: data.model || list[0] || '',
    }
  }

  async function load() {
    setLoading(true)
    setError('')
    try {
      const data = normalizeView(await api.get<AiConfigView>('/api/ai/config'))
      setSaved(data)
      setDraft({ ...data, apiKey: data.apiKey === '******' ? '' : data.apiKey })
    } catch (e: any) {
      setError(e?.message || '加载模型配置失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const isDirty = useMemo(() => {
    if (!saved || !draft) return false
    return (
      saved.enabled !== draft.enabled ||
      saved.baseUrl !== draft.baseUrl ||
      saved.model !== draft.model ||
      !sameModels(saved.models, draft.models) ||
      saved.temperature !== draft.temperature ||
      saved.maxToolRounds !== draft.maxToolRounds ||
      saved.timeoutSeconds !== draft.timeoutSeconds ||
      (draft.apiKey !== '' && draft.apiKey !== '******')
    )
  }, [saved, draft])

  function addModel(rawName: string) {
    if (!draft) return
    const name = rawName.trim()
    if (!name) return
    if (draft.models.includes(name)) {
      setManualModel('')
      return
    }
    const nextModels = [...draft.models, name]
    setDraft({
      ...draft,
      models: nextModels,
      model: draft.model || name,
      enabled: true,
    })
    setManualModel('')
  }

  function toggleModelFromUpstream(name: string) {
    if (!draft) return
    if (draft.models.includes(name)) {
      removeModel(name)
    } else {
      addModel(name)
    }
  }

  function removeModel(name: string) {
    if (!draft) return
    const nextModels = draft.models.filter((m) => m !== name)
    const nextDefault = draft.model === name ? (nextModels[0] || '') : draft.model
    setDraft({
      ...draft,
      models: nextModels,
      model: nextDefault,
    })
  }

  function setDefaultModel(name: string) {
    if (!draft) return
    setDraft({ ...draft, model: name })
    setTestResult(null)
  }

  async function onFetchUpstreamModels() {
    if (!draft) return
    setFetchingModels(true)
    setError('')
    setMsg('')
    try {
      const list = await api.post<string[]>('/api/ai/config/models', {
        baseUrl: draft.baseUrl,
        apiKey: draft.apiKey,
      })
      setUpstreamModels(list || [])
      setUpstreamOpen(true)
      setUpstreamKw('')
      setMsg(list && list.length > 0 ? `已从上游获取 ${list.length} 个可用模型，点击即可添加或取消` : '上游返回空模型列表，请手动输入模型名称添加')
    } catch (e: any) {
      setError(e?.message || '获取上游模型列表失败')
    } finally {
      setFetchingModels(false)
    }
  }

  async function onSave() {
    if (!draft) return
    setSaving(true)
    setError('')
    setMsg('')
    try {
      const res = normalizeView(await api.post<AiConfigView>('/api/ai/config', {
        enabled: draft.enabled,
        baseUrl: draft.baseUrl,
        apiKey: draft.apiKey,
        model: draft.model,
        models: draft.models,
        temperature: draft.temperature,
        maxToolRounds: draft.maxToolRounds,
        timeoutSeconds: draft.timeoutSeconds,
      }))
      setSaved(res)
      setDraft({ ...res, apiKey: res.apiKey === '******' ? '' : res.apiKey })
      setMsg('模型配置已保存并立即生效')
    } catch (e: any) {
      setError(e?.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  async function onTest(targetModel?: string) {
    if (!draft) return
    const modelToTest = (targetModel || draft.model || '').trim()
    setTestingModel(modelToTest || '__default__')
    setError('')
    setWarn('')
    setMsg('')
    try {
      const res = await api.post<any>('/api/ai/config/test', {
        baseUrl: draft.baseUrl,
        apiKey: draft.apiKey,
        model: modelToTest,
        models: draft.models,
      })
      const modelName = res.model || modelToTest
      const parsed: TestResult = {
        model: modelName,
        latencyMs: res.latencyMs ?? 0,
        reply: res.reply || 'OK',
        toolCallingSupported: Boolean(res.toolCallingSupported),
        toolCallStatus: (res.toolCallStatus as TestResult['toolCallStatus']) || (res.toolCallingSupported ? 'ok' : 'skipped'),
        toolCallLatencyMs: res.toolCallLatencyMs ?? 0,
        toolCallName: res.toolCallName || '',
        toolCallDetail: res.toolCallDetail || '',
      }
      setTestResult(parsed)
      setTestedMap((prev) => ({ ...prev, [modelName]: parsed }))
      if (parsed.toolCallingSupported) {
        setMsg(`自检通过：${modelName}（连通 ${parsed.latencyMs} ms · Tool Calling 支持，${parsed.toolCallLatencyMs} ms）`)
      } else {
        setWarn(`模型 ${modelName} 接口可连通（${parsed.latencyMs} ms），但 Tool Calling 自检未通过：${parsed.toolCallDetail}`)
      }
    } catch (e: any) {
      setError(e?.message || '连接测试失败')
    } finally {
      setTestingModel(null)
    }
  }

  const filteredUpstream = useMemo(() => {
    const q = upstreamKw.trim().toLowerCase()
    if (!q) return upstreamModels
    return upstreamModels.filter((m) => m.toLowerCase().includes(q))
  }, [upstreamModels, upstreamKw])

  if (loading && !draft) {
    return (
      <div className="card" style={{ padding: 24 }}>
        <TableSkeleton rows={5} cols={3} />
      </div>
    )
  }

  if (!draft) {
    return (
      <div className="card">
        <div className="error-banner">{error || '无法加载模型配置'}</div>
        <button type="button" className="btn sm" onClick={load}>重试</button>
      </div>
    )
  }

  return (
    <>
      <div className="page-head compact">
        <div className="row" style={{ gap: 12, minWidth: 0 }}>
          <div className="icon-tile sm"><Icon name="config" size={18} /></div>
          <div style={{ minWidth: 0 }}>
            <h1>模型配置</h1>
            <p className="one-line">默认基于 OpenAI Chat Completions 协议接入，支持从上游自动拉取与配置多个可用模型</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm primary" to="/ai">
            <Icon name="sparkle" size={13} />前往智能问数
          </Link>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {warn && (
        <div className="ai-warn-banner">
          <Icon name="alert" size={14} />
          <span>{warn}</span>
        </div>
      )}
      {msg && (
        <div className="sync-notice">
          <Icon name="check" size={14} />
          <span>{msg}</span>
        </div>
      )}

      <div className="sync-cfg-grid">
        {/* 左侧：配置表单 */}
        <div className="sync-cfg-main">
          {/* 1. OpenAI Chat Completions 接入参数 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>OpenAI Chat Completions 接口</h3>
                <p className="muted">填写标准 OpenAI 兼容服务的 Base URL 与 API Key，系统自动调用 /chat/completions 与 /models</p>
              </div>
              <label className="sync-toggle-inline">
                <span>{draft.enabled ? '已启用' : '已停用'}</span>
                <button
                  type="button"
                  role="switch"
                  aria-checked={draft.enabled}
                  className={`sync-switch${draft.enabled ? ' on' : ''}`}
                  onClick={() => setDraft({ ...draft, enabled: !draft.enabled })}
                >
                  <span className="sync-switch-thumb" />
                </button>
              </label>
            </div>

            <div className="form-grid">
              <div className="field">
                <label>API Base URL <span className="req">*</span></label>
                <input
                  className="input mono"
                  placeholder="https://api.example.com/v1"
                  value={draft.baseUrl}
                  onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
                />
                <span className="hint">填写到版本前缀即可，系统会自动补全 /chat/completions</span>
              </div>
              <div className="field">
                <label>
                  API Key <span className="req">*</span>
                  {draft.hasApiKey && <span className="muted">（已保存，留空不覆盖）</span>}
                </label>
                <input
                  className="input mono"
                  type="password"
                  autoComplete="new-password"
                  placeholder={draft.hasApiKey ? '******（留空保留原密钥）' : 'sk-...'}
                  value={draft.apiKey}
                  onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
                />
                <span className="hint">仅服务端保存并用于鉴权，绝不回传明文至浏览器</span>
              </div>
            </div>
          </section>

          {/* 2. 多模型配置与上游自动获取 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>模型列表（支持多模型）</h3>
                <p className="muted">可点击「从上游获取模型」勾选多个模型，或手动输入添加；点击已配置模型可切换为默认模型，问数页支持随时切换</p>
              </div>
              <button
                type="button"
                className="btn sm"
                disabled={fetchingModels}
                onClick={onFetchUpstreamModels}
              >
                <Icon name="refresh" size={13} className={fetchingModels ? 'spin' : ''} />
                {fetchingModels ? '获取中…' : '从上游获取模型'}
              </button>
            </div>

            {/* 已配置模型芯片区 */}
            <div className="ai-models-box">
              <div className="ai-models-box-head">
                <span>已配置模型（{draft.models.length}）</span>
                {draft.model && <span className="muted">当前默认：<b className="mono" style={{ color: 'var(--accent)' }}>{draft.model}</b></span>}
              </div>
              {draft.models.length === 0 ? (
                <div className="ai-models-empty">
                  暂未添加模型。请先点击右上角「从上游获取模型」选取，或在下方手动输入模型名称添加。
                </div>
              ) : (
                <div className="ai-configured-tags">
                  {draft.models.map((m) => {
                    const isDef = m === draft.model
                    const tested = testedMap[m]
                    const isThisTesting = testingModel === m
                    return (
                      <div key={m} className={`ai-model-tag${isDef ? ' default' : ''}`}>
                        <button
                          type="button"
                          className="ai-model-tag-main mono"
                          onClick={() => setDefaultModel(m)}
                          title={isDef ? '当前默认模型' : '点击设为默认模型'}
                        >
                          {isDef && <em className="ai-default-pill">默认</em>}
                          <span>{m}</span>
                          {tested && (
                            <span
                              className={`ai-tc-dot ${tested.toolCallingSupported ? 'ok' : 'warn'}`}
                              title={tested.toolCallingSupported ? `Tool Calling 支持（${tested.toolCallLatencyMs} ms）` : `Tool Calling 未通过：${tested.toolCallDetail}`}
                            >
                              {tested.toolCallingSupported ? 'TC✓' : 'TC✗'}
                            </span>
                          )}
                        </button>
                        <button
                          type="button"
                          className="ai-model-tag-test"
                          aria-label={`自检模型 ${m}`}
                          title={`测试 ${m} 连通性与 Tool Calling 能力`}
                          disabled={testing}
                          onClick={() => onTest(m)}
                        >
                          <Icon name={isThisTesting ? 'refresh' : 'bolt'} size={11} className={isThisTesting ? 'spin' : ''} />
                        </button>
                        <button
                          type="button"
                          className="ai-model-tag-del"
                          aria-label={`移除模型 ${m}`}
                          title="移除该模型"
                          onClick={() => removeModel(m)}
                        >
                          <Icon name="close" size={11} />
                        </button>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>

            {/* 手动添加模型 */}
            <div className="ai-manual-add">
              <input
                className="input mono"
                placeholder="手动输入模型名称（如 qwen-plus / glm-4-plus / deepseek-chat），回车添加"
                value={manualModel}
                onChange={(e) => setManualModel(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    addModel(manualModel)
                  }
                }}
              />
              <button
                type="button"
                className="btn sm"
                disabled={!manualModel.trim()}
                onClick={() => addModel(manualModel)}
              >
                <Icon name="plus" size={13} />添加模型
              </button>
            </div>

            {/* 上游拉取到的可选模型面板 */}
            {upstreamOpen && (
              <div className="ai-upstream-panel">
                <div className="ai-upstream-head">
                  <div className="ai-side-search" style={{ flex: 1, maxWidth: 320 }}>
                    <Icon name="search" size={13} />
                    <input
                      className="input"
                      placeholder={`筛选上游模型（共 ${upstreamModels.length} 个）…`}
                      value={upstreamKw}
                      onChange={(e) => setUpstreamKw(e.target.value)}
                    />
                  </div>
                  <button type="button" className="btn sm ghost" onClick={() => setUpstreamOpen(false)}>
                    收起列表
                  </button>
                </div>
                {filteredUpstream.length === 0 ? (
                  <div className="ai-models-empty">没有匹配的上游模型</div>
                ) : (
                  <div className="ai-upstream-grid">
                    {filteredUpstream.map((m) => {
                      const picked = draft.models.includes(m)
                      return (
                        <button
                          type="button"
                          key={m}
                          className={`ai-upstream-item mono${picked ? ' picked' : ''}`}
                          onClick={() => toggleModelFromUpstream(m)}
                          title={picked ? '点击移出已配置列表' : '点击加入已配置列表'}
                        >
                          <span className="ai-upstream-check">
                            {picked && <Icon name="check" size={11} />}
                          </span>
                          <span className="ai-upstream-name">{m}</span>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            )}
          </section>

          {/* 3. Agent 推理与安全护栏参数 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>Agent 运行参数</h3>
                <p className="muted">控制回答严谨度、多步查数循环上限与单次请求超时时间</p>
              </div>
            </div>
            <div className="form-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
              <div className="field">
                <label>Temperature（0.0 – 1.5）</label>
                <input
                  className="input mono"
                  type="number"
                  step="0.1"
                  min="0"
                  max="1.5"
                  value={draft.temperature}
                  onChange={(e) => setDraft({ ...draft, temperature: Number(e.target.value) })}
                />
                <span className="hint">推荐 0.1 – 0.3，数据问答更严谨</span>
              </div>
              <div className="field">
                <label>最大工具轮次（1 – 8）</label>
                <input
                  className="input mono"
                  type="number"
                  min="1"
                  max="8"
                  value={draft.maxToolRounds}
                  onChange={(e) => setDraft({ ...draft, maxToolRounds: Number(e.target.value) || 4 })}
                />
                <span className="hint">单次问答允许的多步查数上限</span>
              </div>
              <div className="field">
                <label>请求超时（秒，10 – 180）</label>
                <input
                  className="input mono"
                  type="number"
                  min="10"
                  max="180"
                  value={draft.timeoutSeconds}
                  onChange={(e) => setDraft({ ...draft, timeoutSeconds: Number(e.target.value) || 60 })}
                />
                <span className="hint">上游大模型响应超时上限</span>
              </div>
            </div>
          </section>

          {/* 底部保存栏 */}
          <div className={`sync-save-bar${isDirty ? ' dirty' : ''}`}>
            <div className="sync-save-state">
              {isDirty ? (
                <>
                  <span className="sync-dirty-dot" />
                  <span>有未保存的配置更改</span>
                </>
              ) : (
                <span className="muted">当前配置与服务端一致</span>
              )}
            </div>
            <div className="row" style={{ gap: 8 }}>
              <button
                type="button"
                className="btn sm"
                disabled={testing}
                onClick={() => onTest()}
                title="检测基础连通性与 Tool Calling 能力"
              >
                {testing ? '自检中…' : '测试连接'}
              </button>
              <button
                type="button"
                className="btn sm"
                disabled={!isDirty || saving}
                onClick={() => {
                  if (saved) setDraft({ ...saved, apiKey: '' })
                  setError('')
                  setWarn('')
                  setMsg('')
                }}
              >
                撤销修改
              </button>
              <button
                type="button"
                className="btn sm primary"
                disabled={!isDirty || saving}
                onClick={onSave}
              >
                {saving ? '保存中…' : '保存配置'}
              </button>
            </div>
          </div>
        </div>

        {/* 右侧：当前状态与连通性测试 */}
        <aside className="sync-cfg-aside">
          <div className="card">
            <div className="rel-insp-head">
              <span className={`badge ${saved?.ready ? 'ok' : 'warn'}`}>
                {saved?.ready ? '已就绪' : '未完成配置'}
              </span>
            </div>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>当前服务状态</h3>
            <div className="rel-insp-kv">
              <div><span>服务开关</span><b>{saved?.enabled ? '已启用' : '已停用'}</b></div>
              <div><span>默认模型</span><b className="mono">{saved?.model || '—'}</b></div>
              <div><span>已配模型数</span><b>{saved?.models?.length || 0} 个</b></div>
              <div><span>接口地址</span><b className="mono">{saved?.baseUrl || '—'}</b></div>
              <div><span>API Key</span><b className="mono">{saved?.hasApiKey ? '已配置（******）' : '未配置'}</b></div>
            </div>

            {testResult && (
              <div className={`ai-test-card ${testResult.toolCallingSupported ? 'ok' : 'warn'}`}>
                <div className="ai-test-card-head">
                  <span className="mono">{testResult.model}</span>
                  <span className={`badge ${testResult.toolCallingSupported ? 'ok' : 'warn'}`}>
                    {testResult.toolCallingSupported ? '适合问数' : '不建议问数'}
                  </span>
                </div>
                <div className="ai-test-rows">
                  <div className="ai-test-row">
                    <span>接口连通性</span>
                    <b className="ok">正常 · {testResult.latencyMs} ms</b>
                  </div>
                  <div className="ai-test-row">
                    <span>Tool Calling</span>
                    <b className={testResult.toolCallingSupported ? 'ok' : 'warn'}>
                      {testResult.toolCallingSupported
                        ? `支持 · ${testResult.toolCallLatencyMs} ms`
                        : (testResult.toolCallStatus === 'error' ? '接口不支持' : '未触发工具')}
                    </b>
                  </div>
                </div>
                {testResult.toolCallDetail && (
                  <div className="ai-test-detail">{testResult.toolCallDetail}</div>
                )}
              </div>
            )}

            <div className="stack" style={{ gap: 8, marginTop: 14 }}>
              <button
                type="button"
                className="btn"
                style={{ width: '100%' }}
                disabled={testing}
                onClick={() => onTest()}
              >
                <Icon name="bolt" size={14} />{testing ? '正在检测连通性与 Tool Calling…' : '测试连接与 Tool Calling'}
              </button>
              <Link className="btn primary" style={{ width: '100%' }} to="/ai">
                <Icon name="sparkle" size={14} />前往「智能问数」体验
              </Link>
            </div>
          </div>

          <div className="card">
            <h3 style={{ margin: '0 0 8px', fontSize: 14 }}>协议与安全说明</h3>
            <p className="rel-insp-note" style={{ marginBottom: 0 }}>
              默认采用标准 OpenAI Chat Completions 协议（支持 Function Calling）；AI 仅开放 6 个只读业务工具，所有金额、吨位、排名与环比均由后端 Java 引擎精确计算。
            </p>
          </div>
        </aside>
      </div>
    </>
  )
}
