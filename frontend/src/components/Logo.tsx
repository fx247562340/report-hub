import { useId } from 'react'

/**
 * REPORT HUB 品牌标识
 * 棱镜数据面板 + 三阶跃升柱体与汇流光弧，诠释「多源数据汇聚 → 智能经营洞察」
 */
export function LogoMark({ size = 36, className = '' }: { size?: number; className?: string }) {
  const uid = useId().replace(/:/g, '')
  const gMain = `rh-main-${uid}`
  const gDim = `rh-dim-${uid}`
  const gRing = `rh-ring-${uid}`

  return (
    <svg
      className={`logo-mark ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      aria-hidden
    >
      <defs>
        <linearGradient id={gMain} x1="12" y1="8" x2="38" y2="40" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#9dffb0" />
          <stop offset="55%" stopColor="#5dff7a" />
          <stop offset="100%" stopColor="#16b84e" />
        </linearGradient>
        <linearGradient id={gDim} x1="10" y1="18" x2="26" y2="38" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#5dff7a" stopOpacity="0.62" />
          <stop offset="100%" stopColor="#1c7a42" stopOpacity="0.32" />
        </linearGradient>
        <linearGradient id={gRing} x1="6" y1="6" x2="42" y2="42" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#7dff96" stopOpacity="0.55" />
          <stop offset="100%" stopColor="#5dff7a" stopOpacity="0.08" />
        </linearGradient>
      </defs>

      {/* 外缘棱角框 */}
      <rect
        x="4.5"
        y="4.5"
        width="39"
        height="39"
        rx="11"
        stroke={`url(#${gRing})`}
        strokeWidth="1.4"
        fill="#0d1912"
      />

      {/* 底部基准汇流轨 */}
      <path
        d="M12.5 34.5H35.5"
        stroke="#5dff7a"
        strokeOpacity="0.35"
        strokeWidth="1.6"
        strokeLinecap="round"
      />

      {/* 左柱：原始数据层 */}
      <rect x="13" y="24" width="5.5" height="8" rx="2" fill={`url(#${gDim})`} />

      {/* 中柱：融合核算层 */}
      <rect x="21.25" y="18.5" width="5.5" height="13.5" rx="2" fill={`url(#${gMain})`} fillOpacity="0.72" />

      {/* 右柱：经营洞察主峰（斜切棱镜柱） */}
      <path
        d="M29.5 14.5C29.5 13.4 30.4 12.5 31.5 12.5H33C34.1 12.5 35 13.4 35 14.5V30C35 31.1 34.1 32 33 32H31.5C30.4 32 29.5 31.1 29.5 30V14.5Z"
        fill={`url(#${gMain})`}
      />

      {/* 上行趋势折光弧 + 顶点光核 */}
      <path
        d="M15.75 20.5L24 14.8L32.25 10.5"
        stroke="#b8ffca"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeOpacity="0.88"
      />
      <circle cx="32.25" cy="10.5" r="2.2" fill="#e6ffec" />
    </svg>
  )
}

export function Logo({
  size = 34,
  title = 'REPORT HUB',
  subtitle = '智能经营报表',
  stacked = false,
}: {
  size?: number
  title?: string
  subtitle?: string
  stacked?: boolean
}) {
  const parts = title ? title.split(' ') : []
  const isReportHub = parts.length === 2 && parts[0] === 'REPORT' && parts[1] === 'HUB'

  return (
    <div className={`logo-lockup ${stacked ? 'stacked' : ''}`.trim()}>
      <div
        className="logo-tile"
        style={{ width: size + 4, height: size + 4, borderRadius: Math.round(size * 0.3) }}
      >
        <LogoMark size={size} />
      </div>
      {title && (
        <div className="logo-type">
          <div className="logo-title">
            {isReportHub ? (
              <>
                <span>REPORT</span>
                <span className="logo-title-accent">HUB</span>
              </>
            ) : (
              title
            )}
          </div>
          {subtitle && <div className="logo-sub">{subtitle}</div>}
        </div>
      )}
    </div>
  )
}

export default Logo
