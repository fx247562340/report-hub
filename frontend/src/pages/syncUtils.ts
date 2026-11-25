export type SyncStatus = {
  datasetCode: string
  name?: string
  endpointCode?: string
  endpointMethod?: string
  endpointPath?: string
  dataSourceCode?: string
  dataSourceName?: string
  enabled: boolean
  mode: string
  parentDataset?: string
  keyPath?: string
  keysSql?: string
  keysEndpoint?: string
  incrementalFilter?: string
  hasIncrementalFilter?: boolean
  scheduledStrategy?: 'incremental' | 'full'
  pageSize: number
  maxPages: number
  notes?: string
  recordCount: number
  lastSyncedAt?: string
  scheduleType?: 'manual' | 'interval' | 'daily' | 'weekly' | string
  scheduleTime?: string
  scheduleWeekday?: number
  scheduleIntervalMin?: number
  nextScheduleRun?: string
  lastScheduleRun?: string
  lastJobStatus?: string
  lastJobRows?: number
  lastJobError?: string
  lastJobDurationMs?: number
  lastJobTrigger?: string
}

export type SyncJob = {
  id: number
  datasetCode: string
  datasetName?: string
  status: string
  startedAt: string
  finishedAt?: string
  rowCount: number
  pageCount: number
  durationMs?: number
  error?: string
  trigger: string
}

export const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function fmtTime(s?: string) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}

export function fmtShortTime(s?: string) {
  if (!s) return '—'
  const str = String(s).replace('T', ' ')
  return str.length >= 16 ? str.slice(5, 16) : str
}

export function fmtDuration(ms?: number | null) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  const min = Math.floor(ms / 60_000)
  const sec = Math.round((ms % 60_000) / 1000)
  return `${min}分${sec}秒`
}

export function formatScheduleSummary(x: {
  scheduleType?: string
  scheduleTime?: string
  scheduleWeekday?: number
  scheduleIntervalMin?: number
}) {
  const type = x.scheduleType || 'manual'
  if (type === 'daily') return `每天 ${x.scheduleTime || '02:00'}`
  if (type === 'weekly') {
    const idx = Math.max(1, Math.min(7, Number(x.scheduleWeekday || 7))) - 1
    return `每${WEEKDAYS[idx]} ${x.scheduleTime || '02:00'}`
  }
  if (type === 'interval') return `每隔 ${x.scheduleIntervalMin || 60} 分钟`
  return '仅手动触发'
}

export function modeLabel(x: Pick<SyncStatus, 'mode' | 'hasIncrementalFilter'>) {
  if (x.mode === 'keys') return '表级全量'
  if (x.mode === 'related') return '关联拉取'
  return x.hasIncrementalFilter ? '列表增量' : '列表全量'
}

export function statusInfo(status?: string): { label: string; tone: 'ok' | 'err' | 'warn' | 'muted' } {
  switch (status) {
    case 'success':
      return { label: '成功', tone: 'ok' }
    case 'failed':
      return { label: '失败', tone: 'err' }
    case 'running':
      return { label: '同步中', tone: 'warn' }
    case 'queued':
      return { label: '排队中', tone: 'warn' }
    case 'cancelled':
      return { label: '已取消', tone: 'muted' }
    default:
      return { label: '未同步', tone: 'muted' }
  }
}

export function triggerInfo(trigger?: string): { label: string; isSchedule: boolean; isFull: boolean } {
  const t = (trigger || 'manual').toLowerCase()
  const isSchedule = t.startsWith('schedule')
  const isFull = t.includes('full')
  if (isSchedule) {
    return { label: isFull ? '定时全量' : '定时增量', isSchedule: true, isFull }
  }
  return { label: isFull ? '手动全量' : '手动增量', isSchedule: false, isFull }
}
