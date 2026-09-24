/**
 * REPORT HUB 统一标识
 * 立方数据节点 + 右侧汇总刻度，表达「多源聚合 → 一张报表」
 */
export function LogoMark({ size = 36, className = '' }: { size?: number; className?: string }) {
  return (
    <svg
      className={`logo-mark ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      aria-hidden
    >
      {/* 外框六边形轮廓（工程感） */}
      <path
        d="M24 5.5 40.5 15v18L24 42.5 7.5 33V15L24 5.5Z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinejoin="round"
        opacity=".9"
      />
      {/* 中心数据立方 */}
      <path
        d="M24 14.5 32 19v10L24 33.5 16 29V19L24 14.5Z"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      {/* 多源汇入连线 */}
      <path
        d="M24 5.5v9M7.5 15l8.5 4M40.5 15 32 19M7.5 33l8.5-4M40.5 33 32 29M24 42.5V33.5"
        stroke="currentColor"
        strokeWidth="1.2"
        opacity=".55"
        strokeLinecap="round"
      />
      {/* 中心节点 */}
      <circle cx="24" cy="24" r="2.2" fill="currentColor" />
    </svg>
  )
}

export function Logo({
  size = 36,
  title = 'REPORT HUB',
  subtitle = '多源关联报表',
  stacked = false,
}: {
  size?: number
  title?: string
  subtitle?: string
  stacked?: boolean
}) {
  return (
    <div className={`logo-lockup ${stacked ? 'stacked' : ''}`.trim()}>
      <div className="logo-tile" style={{ width: size + 8, height: size + 8, borderRadius: Math.round(size * 0.28) }}>
        <LogoMark size={size} />
      </div>
      {title && (
        <div className="logo-type">
          <div className="logo-title">{title}</div>
          {subtitle && <div className="logo-sub">{subtitle}</div>}
        </div>
      )}
    </div>
  )
}

export default Logo
