import type { ReactNode } from 'react'

/** 统一线性图标：16 画布 / 1.75 描边，与深色荧光绿 SaaS 风格对齐 */
export type IconName =
  | 'home'
  | 'report'
  | 'datasource'
  | 'api'
  | 'dataset'
  | 'relation'
  | 'users'
  | 'user'
  | 'help'
  | 'calendar'
  | 'arrowLeft'
  | 'chevronUp'
  | 'chevronDown'
  | 'plus'
  | 'close'
  | 'download'
  | 'search'
  | 'play'
  | 'logout'
  | 'lock'
  | 'database'
  | 'bolt'
  | 'check'
  | 'config'
  | 'sync'
  | 'sidebar'
  | 'menu'
  | 'refresh'
  | 'trend'
  | 'alert'
  | 'chevronRight'
  | 'clock'
  | 'sparkle'
  | 'send'
  | 'trash'
  | 'copy'

const paths: Record<IconName, ReactNode> = {
  home: (
    <>
      <path d="M2.5 7.2 8 2.5l5.5 4.7" />
      <path d="M4 8.2v5.3h8V8.2" />
      <path d="M6.5 13.5V10h3v3.5" />
    </>
  ),
  report: (
    <>
      <rect x="2.5" y="2.5" width="11" height="11" rx="1.5" />
      <path d="M2.5 6h11M2.5 10h11M6 2.5v11M10 2.5v11" />
    </>
  ),
  datasource: (
    <>
      <ellipse cx="8" cy="4.2" rx="5" ry="2" />
      <path d="M3 4.2v7.6c0 1.1 2.2 2 5 2s5-.9 5-2V4.2" />
      <path d="M3 8c0 1.1 2.2 2 5 2s5-.9 5-2" />
    </>
  ),
  api: (
    <>
      <path d="M5.5 4.5 2.5 8l3 3.5" />
      <path d="M10.5 4.5 13.5 8l-3 3.5" />
      <path d="M9.2 3.2 6.8 12.8" />
    </>
  ),
  dataset: (
    <>
      <rect x="2.5" y="3" width="11" height="3" rx="1" />
      <rect x="2.5" y="7" width="11" height="3" rx="1" />
      <rect x="2.5" y="11" width="11" height="2.5" rx="1" />
    </>
  ),
  relation: (
    <>
      <circle cx="4.2" cy="8" r="2" />
      <circle cx="11.8" cy="4.5" r="2" />
      <circle cx="11.8" cy="11.5" r="2" />
      <path d="M6 7.2 10 5.2M6 8.8 10 10.8" />
    </>
  ),
  users: (
    <>
      <circle cx="6" cy="5.5" r="2.2" />
      <path d="M2.2 13c.4-2.2 2-3.4 3.8-3.4S9.4 10.8 9.8 13" />
      <circle cx="11.2" cy="6.2" r="1.7" />
      <path d="M10.2 9.4c1.6.2 3 1.2 3.4 3.1" />
    </>
  ),
  user: (
    <>
      <circle cx="8" cy="5.5" r="2.4" />
      <path d="M3.2 13.2c.5-2.5 2.4-3.8 4.8-3.8s4.3 1.3 4.8 3.8" />
    </>
  ),
  help: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M6.3 6.2a1.8 1.8 0 1 1 2.4 1.7c-.5.2-.8.6-.8 1.2" />
      <path d="M8 11.6h.01" />
    </>
  ),
  calendar: (
    <>
      <rect x="2.5" y="3.5" width="11" height="10" rx="1.5" />
      <path d="M2.5 6.5h11M5.5 2.5v2M10.5 2.5v2" />
    </>
  ),
  arrowLeft: <path d="M12.5 8h-9M6.5 4.5 3 8l3.5 3.5" />,
  chevronUp: <path d="M4 10.5 8 6.5l4 4" />,
  chevronDown: <path d="M4 6.5 8 10.5l4-4" />,
  plus: <path d="M8 3.5v9M3.5 8h9" />,
  close: <path d="M4.5 4.5l7 7M11.5 4.5l-7 7" />,
  download: (
    <>
      <path d="M8 2.8v7.4" />
      <path d="M5.2 7.5 8 10.3l2.8-2.8" />
      <path d="M3 12.5h10" />
    </>
  ),
  search: (
    <>
      <circle cx="7.2" cy="7.2" r="4.2" />
      <path d="M10.4 10.4 13.2 13.2" />
    </>
  ),
  play: <path d="M5.5 3.8v8.4L12.2 8z" />,
  logout: (
    <>
      <path d="M6.5 3.5H4.2A1.2 1.2 0 0 0 3 4.7v6.6a1.2 1.2 0 0 0 1.2 1.2h2.3" />
      <path d="M9.5 5.5 12.5 8l-3 2.5" />
      <path d="M7 8h5.5" />
    </>
  ),
  lock: (
    <>
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
      <path d="M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7" />
    </>
  ),
  database: (
    <>
      <rect x="3" y="2.5" width="10" height="4" rx="1.2" />
      <rect x="3" y="8.5" width="10" height="4" rx="1.2" />
      <path d="M5.5 4.5h.01M5.5 10.5h.01" />
    </>
  ),
  bolt: <path d="M9.2 2.5 4.5 9h3.2l-.9 4.5L11.5 7H8.3z" />,
  check: <path d="M3.5 8.2 6.5 11.2 12.5 5" />,
  config: (
    <>
      <circle cx="8" cy="8" r="2.2" />
      <path d="M8 2.5v2M8 11.5v2M2.5 8h2M11.5 8h2M4.1 4.1l1.4 1.4M10.5 10.5l1.4 1.4M11.9 4.1l-1.4 1.4M5.5 10.5l-1.4 1.4" />
    </>
  ),
  sync: (
    <>
      <path d="M13 6A5.2 5.2 0 0 0 3.8 4.8" />
      <path d="M3.5 2.5v2.7h2.7" />
      <path d="M3 10a5.2 5.2 0 0 0 9.2 1.2" />
      <path d="M12.5 13.5v-2.7H9.8" />
    </>
  ),
  sidebar: (
    <>
      <rect x="2.2" y="2.8" width="11.6" height="10.4" rx="1.6" />
      <path d="M6 2.8v10.4" />
    </>
  ),
  menu: <path d="M2.5 4.5h11M2.5 8h11M2.5 11.5h11" />,
  refresh: (
    <>
      <path d="M13 8a5 5 0 1 1-1.3-3.4" />
      <path d="M13 2.8v2.6h-2.6" />
    </>
  ),
  trend: (
    <>
      <path d="M2.5 11.5 6.2 7.8l2.5 2.2 4.8-5.5" />
      <path d="M10.5 4.5h3v3" />
    </>
  ),
  alert: (
    <>
      <path d="M8 2.6 13.8 12.8H2.2z" />
      <path d="M8 6.4v3.2" />
      <path d="M8 11.3h.01" />
    </>
  ),
  chevronRight: <path d="M6.2 4 10.2 8l-4 4" />,
  clock: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M8 5v3.2l2.2 1.4" />
    </>
  ),
  sparkle: (
    <>
      <path d="M8 2.2 9.4 6.2 13.5 7.6 9.4 9 8 13.2 6.6 9 2.5 7.6 6.6 6.2Z" />
      <path d="M12.5 2.5v2M11.5 3.5h2" />
    </>
  ),
  send: (
    <>
      <path d="M13.5 2.5 7.2 8.8" />
      <path d="M13.5 2.5 9.4 13.5 7.2 8.8 2.5 6.6 13.5 2.5Z" />
    </>
  ),
  trash: (
    <>
      <path d="M3 4.5h10M6 4.5V3h4v1.5M4.5 4.5l.6 8.2a1 1 0 0 0 1 .8h3.8a1 1 0 0 0 1-.8l.6-8.2" />
    </>
  ),
  copy: (
    <>
      <rect x="5.5" y="5.5" width="7.5" height="7.5" rx="1.4" />
      <path d="M3.5 10.5V4.2A1.2 1.2 0 0 1 4.7 3h6.3" />
    </>
  ),
}

export function Icon({
  name,
  size = 16,
  className = '',
  strokeWidth = 1.75,
}: {
  name: IconName
  size?: number
  className?: string
  strokeWidth?: number
}) {
  return (
    <svg
      className={`icon-svg ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {paths[name]}
    </svg>
  )
}

export default Icon
