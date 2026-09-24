import type { ReactNode } from 'react'
import Icon from './Icon'

type Variant = 'default' | 'report' | 'data' | 'search' | 'config' | 'link'

const art: Record<Variant, ReactNode> = {
  default: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <rect x="18" y="16" width="84" height="52" rx="8" className="ea-panel" />
      <rect x="28" y="28" width="36" height="6" rx="3" className="ea-line" />
      <rect x="28" y="40" width="56" height="4" rx="2" className="ea-line dim" />
      <rect x="28" y="50" width="44" height="4" rx="2" className="ea-line dim" />
      <circle cx="92" cy="24" r="10" className="ea-dot" />
    </svg>
  ),
  report: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <rect x="22" y="12" width="76" height="56" rx="8" className="ea-panel" />
      <path d="M34 28h52M34 40h52M34 52h36" className="ea-line" />
      <circle cx="88" cy="56" r="12" className="ea-dot" />
      <path d="M82 56h12M88 50v12" className="ea-stroke" />
    </svg>
  ),
  data: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <ellipse cx="60" cy="22" rx="28" ry="10" className="ea-panel" />
      <path d="M32 22v28c0 6 12 10 28 10s28-4 28-10V22" className="ea-panel" />
      <path d="M32 36c0 6 12 10 28 10s28-4 28-10" className="ea-stroke" />
    </svg>
  ),
  search: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <circle cx="54" cy="36" r="18" className="ea-panel" />
      <path d="M68 50l16 16" className="ea-stroke" />
      <rect x="42" y="32" width="24" height="4" rx="2" className="ea-line dim" />
    </svg>
  ),
  config: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <rect x="24" y="18" width="28" height="20" rx="5" className="ea-panel" />
      <rect x="68" y="18" width="28" height="20" rx="5" className="ea-panel" />
      <rect x="46" y="48" width="28" height="20" rx="5" className="ea-panel" />
      <path d="M52 28h16M38 38v10h22M82 38v10H60" className="ea-stroke" />
    </svg>
  ),
  link: (
    <svg viewBox="0 0 120 80" className="empty-art" aria-hidden>
      <circle cx="34" cy="40" r="12" className="ea-panel" />
      <circle cx="86" cy="24" r="10" className="ea-panel" />
      <circle cx="86" cy="56" r="10" className="ea-panel" />
      <path d="M46 36l30-10M46 44l30 10" className="ea-stroke" />
    </svg>
  ),
}

export default function EmptyState({
  title = '暂无数据',
  desc,
  variant = 'default',
  action,
  compact = false,
}: {
  title?: string
  desc?: string
  variant?: Variant
  action?: ReactNode
  compact?: boolean
}) {
  return (
    <div className={`empty-state ${compact ? 'compact' : ''}`}>
      <div className="empty-art-wrap">{art[variant]}</div>
      <div className="empty-title">{title}</div>
      {desc && <div className="empty-desc">{desc}</div>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}

export function InlineEmpty({ text, icon = 'search' }: { text: string; icon?: 'search' | 'config' | 'plus' }) {
  return (
    <div className="inline-empty">
      <Icon name={icon as any} size={14} />
      {text}
    </div>
  )
}
