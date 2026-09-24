export function SkeletonText({ width = '100%', height = 12 }: { width?: number | string; height?: number }) {
  return <span className="skel" style={{ width, height, borderRadius: 6 }} />
}

export function SkeletonRow({ cols = 4 }: { cols?: number }) {
  return (
    <div className="skel-row">
      {Array.from({ length: cols }).map((_, i) => (
        <SkeletonText key={i} width={i === 0 ? '40%' : i === cols - 1 ? '20%' : '55%'} />
      ))}
    </div>
  )
}

export function TableSkeleton({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="skel-table" aria-busy>
      {Array.from({ length: rows }).map((_, i) => (
        <SkeletonRow key={i} cols={cols} />
      ))}
    </div>
  )
}

export function CardSkeleton({ count = 3 }: { count?: number }) {
  return (
    <div className="skel-cards" aria-busy>
      {Array.from({ length: count }).map((_, i) => (
        <div className="skel-card" key={i}>
          <SkeletonText width="45%" height={11} />
          <SkeletonText width="30%" height={28} />
          <SkeletonText width="70%" height={10} />
        </div>
      ))}
    </div>
  )
}

export function ListSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div className="skel-list" aria-busy>
      {Array.from({ length: rows }).map((_, i) => (
        <div className="skel-list-item" key={i}>
          <SkeletonText width="35%" height={14} />
          <SkeletonText width="50%" height={11} />
        </div>
      ))}
    </div>
  )
}
