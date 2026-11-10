import type { Assignment, HistoryRow } from '@/api/delivery'
import { formatTime, formatVnd } from '@/lib/format'

import { isPrepaid } from './order'

const pad = (n: number) => String(n).padStart(2, '0')
const dayMonth = (d: Date) => `${pad(d.getDate())}/${pad(d.getMonth() + 1)}`
const dayKey = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`

function label(row: HistoryRow): string | null {
  switch (row.status) {
    case 'ASSIGNED':
      return 'Nhận phân công'
    case 'PICKED_UP':
      return 'Đã lấy hàng'
    case 'IN_TRANSIT':
      return row.attempt > 1 ? `Bắt đầu giao lại lần ${row.attempt - 1}` : 'Đang giao'
    case 'FAILED':
      return row.reason ? `Giao thất bại: ${row.reason}` : 'Giao thất bại'
    case 'RETURNING':
      return 'Hoàn về gian hàng'
    case 'RETURNED':
      return 'Đã bàn giao hoàn về'
    case 'DELIVERED':
      return 'Giao thành công'
    default:
      return null
  }
}

/** Lines of the journey card: timeline rows (sorted by time) then footer lines. */
export function buildTimeline(history: HistoryRow[], a: Assignment): string[] {
  const rows = history
    .map((h) => ({ text: label(h), at: new Date(h.occurred_at), failed: h.status === 'FAILED' }))
    .filter((r) => r.text && !Number.isNaN(r.at.getTime()))
    // The final failure and the automatic RETURNING share one timestamp: show the failure first.
    .sort((x, y) => x.at.getTime() - y.at.getTime() || Number(y.failed) - Number(x.failed))
  let lastDay = ''
  const lines = rows.map(({ text, at }) => {
    const key = dayKey(at)
    const date = key === lastDay ? '' : `${dayMonth(at)} · `
    lastDay = key
    return `${date}${formatTime(at)} ${text}`
  })
  if (a.status === 'DELIVERED') {
    lines.push(isPrepaid(a) ? 'Đơn đã thanh toán trước' : `Đã thu COD: ${formatVnd(a.codAmount)}`)
  } else if (a.status === 'RETURNED' && !isPrepaid(a)) {
    lines.push(`COD chưa thu: ${formatVnd(a.codAmount)}`)
  }
  if (a.status === 'DELIVERED' || a.status === 'RETURNED') lines.push('Chia sẻ vị trí đã dừng.')
  return lines
}

/** Reason of the latest FAILED row, for "Lý do trước". */
export function lastFailureReason(history: HistoryRow[]): string | null {
  const failed = history.filter((h) => h.status === 'FAILED' && h.reason)
  return failed.length ? (failed[failed.length - 1].reason ?? null) : null
}
