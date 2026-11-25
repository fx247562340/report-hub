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
  temperature: number
  maxToolRounds: number
  timeoutSeconds: number
}

const PROVIDER_PRESETS = [
  {
    name: '智谱 BigModel',
    desc: 'GLM-4 系列，中文理解与 Tool Calling 稳定',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    model: 'glm-4-plus',
  },
  {
    name: '阿里云百炼',
    desc: '通义千问 Qwen 系列 OpenAI 兼容接口',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen-plus',
  },
  {
    name: 'DeepSeek',
    desc: '高性价比推理与数据分析模型',
    baseUrl: 'https://api.deepseek.com/v1',
    model: 'deepseek-chat',
  },
  {
    name: 'OpenAI / 自定义',
    desc: '兼容 OpenAI、vLLM、Ollama 等标准服务',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini',
  },
]

export default function AiSettings() {
  const [saved, setSaved] = useState<AiConfigView | null>(null)
  const [draft, setDraft] = useState<AiConfigView | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [msg, setMsg] = useState('')
  const [error, setError] = useState('')
  const [testResult, setTestResult] = useState<{ model: string; latencyMs: number; reply: string } | null>(null)

  async function load() {
    setLoading(true)
    setError('')
    try {
      const data = await api.get<AiConfigView>('/api/ai/config')
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
      saved.temperature !== draft.temperature ||
      saved.maxToolRounds !== draft.maxToolRounds ||
      saved.timeoutSeconds !== draft.timeoutSeconds ||
      (draft.apiKey !== '' && draft.apiKey !== '******')
    )
  }, [saved, draft])

  async function onSave() {
    if (!draft) return
    setSaving(true)
    setError('')
    setMsg('')
    try {
      const res = await api.post<AiConfigView>('/api/ai/config', {
        enabled: draft.enabled,
        baseUrl: draft.baseUrl,
        apiKey: draft.apiKey,
        model: draft.model,
        temperature: draft.temperature,
        maxToolRounds: draft.maxToolRounds,
        timeoutSeconds: draft.timeoutSeconds,
      })
      setSaved(res)
      setDraft({ ...res, apiKey: res.apiKey === '******' ? '' : res.apiKey })
      setMsg('模型配置已保存并立即生效')
    } catch (e: any) {
      setError(e?.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  async function onTest() {
    if (!draft) return
    setTesting(true)
    setError('')
    setMsg('')
    setTestResult(null)
    try {
      const res = await api.post<any>('/api/ai/config/test', {
        baseUrl: draft.baseUrl,
        apiKey: draft.apiKey,
        model: draft.model,
      })
      setTestResult({
        model: res.model || draft.model,
        latencyMs: res.latencyMs ?? 0,
        reply: res.reply || 'OK',
      })
      setMsg(`连接测试通过（延迟 ${res.latencyMs ?? 0} ms）`)
    } catch (e: any) {
      setError(e?.message || '连接测试失败')
    } finally {
      setTesting(false)
    }
  }

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
            <p className="one-line">统一管理 OpenAI 兼容协议的大模型接入参数，供「智能问数」调用；API Key 加密存储并脱敏展示</p>
          </div>
        </div>
        <div className="row">
          <Link className="btn sm primary" to="/ai">
            <Icon name="sparkle" size={13} />前往智能问数
          </Link>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {msg && (
        <div className="sync-notice">
          <Icon name="check" size={14} />
          <span>{msg}</span>
        </div>
      )}

      <div className="sync-cfg-grid">
        {/* 左侧：配置表单 */}
        <div className="sync-cfg-main">
          {/* 1. 服务商快捷模板 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>快速接入模板</h3>
                <p className="muted">点击模板自动填入对应平台的 OpenAI 兼容 Base URL 与推荐模型，也可在下方自由修改</p>
              </div>
            </div>
            <div className="sync-mode-cards">
              {PROVIDER_PRESETS.map((p) => {
                const active = draft.baseUrl.replace(/\/+$/, '') === p.baseUrl
                return (
                  <button
                    type="button"
                    key={p.name}
                    className={`sync-mode-card${active ? ' active' : ''}`}
                    onClick={() => {
                      setDraft({ ...draft, baseUrl: p.baseUrl, model: p.model, enabled: true })
                      setTestResult(null)
                    }}
                  >
                    <div className="sync-mode-card-title">
                      <span className="sync-radio-dot" />
                      <b>{p.name}</b>
                    </div>
                    <span className="sync-mode-card-desc">{p.desc}</span>
                  </button>
                )
              })}
            </div>
          </section>

          {/* 2. OpenAI 兼容接口连接参数 */}
          <section className="card sync-cfg-section">
            <div className="sync-sec-head">
              <div>
                <h3>OpenAI 兼容接口参数</h3>
                <p className="muted">支持所有兼容 POST /chat/completions 与 Function Calling 的云端或私有化模型服务</p>
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

            <div className="field">
              <label>API Base URL <span className="req">*</span></label>
              <input
                className="input mono"
                placeholder="https://open.bigmodel.cn/api/paas/v4"
                value={draft.baseUrl}
                onChange={(e) => setDraft({ ...draft, baseUrl: e.target.value })}
              />
              <span className="hint">填写到版本路径即可，系统会自动补全 /chat/completions（不会重复拼接）</span>
            </div>

            <div className="form-grid">
              <div className="field">
                <label>模型名称（Model）<span className="req">*</span></label>
                <input
                  className="input mono"
                  placeholder="如 glm-4-plus / qwen-plus / deepseek-chat"
                  value={draft.model}
                  onChange={(e) => setDraft({ ...draft, model: e.target.value })}
                />
                <span className="hint">建议选用支持 Function Calling（工具调用）的通用模型</span>
              </div>
              <div className="field">
                <label>
                  API Key <span className="req">*</span>
                  {draft.hasApiKey && <span className="muted">（已保存，留空不覆盖原密钥）</span>}
                </label>
                <input
                  className="input mono"
                  type="password"
                  autoComplete="new-password"
                  placeholder={draft.hasApiKey ? '******（留空保留原密钥）' : 'sk-...'}
                  value={draft.apiKey}
                  onChange={(e) => setDraft({ ...draft, apiKey: e.target.value })}
                />
                <span className="hint">仅服务端保存并用于上游鉴权，绝不回传明文至浏览器</span>
              </div>
            </div>
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
                onClick={onTest}
              >
                {testing ? '测试中…' : '测试连接'}
              </button>
              <button
                type="button"
                className="btn sm"
                disabled={!isDirty || saving}
                onClick={() => {
                  if (saved) setDraft({ ...saved, apiKey: '' })
                  setError('')
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
              <div><span>当前模型</span><b className="mono">{saved?.model || '—'}</b></div>
              <div><span>接口地址</span><b className="mono">{saved?.baseUrl || '—'}</b></div>
              <div><span>API Key</span><b className="mono">{saved?.hasApiKey ? '已配置（******）' : '未配置'}</b></div>
            </div>

            {testResult && (
              <div className="sync-notice" style={{ marginTop: 12, marginBottom: 0 }}>
                <Icon name="check" size={14} />
                <span>
                  测试成功 · {testResult.model} · {testResult.latencyMs} ms
                </span>
              </div>
            )}

            <div className="stack" style={{ gap: 8, marginTop: 14 }}>
              <button
                type="button"
                className="btn"
                style={{ width: '100%' }}
                disabled={testing}
                onClick={onTest}
              >
                <Icon name="bolt" size={14} />{testing ? '正在检测连通性…' : '立即测试模型连接'}
              </button>
              <Link className="btn primary" style={{ width: '100%' }} to="/ai">
                <Icon name="sparkle" size={14} />前往「智能问数」体验
              </Link>
            </div>
          </div>

          <div className="card">
            <h3 style={{ margin: '0 0 8px', fontSize: 14 }}>安全与口径说明</h3>
            <p className="rel-insp-note" style={{ marginBottom: 0 }}>
              AI 问数仅开放 6 个只读业务工具，所有金额、吨位、排名与环比均由后端 Java 核算引擎基于本地同步库精确计算，模型无法接触用户密码、数据源密钥或任何写接口。
            </p>
          </div>
        </aside>
      </div>
    </>
  )
}
