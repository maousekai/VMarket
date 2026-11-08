import { formatDate } from '@/lib/format'

const DAY = 24 * 60 * 60 * 1000

/** 'hôm nay', 'hôm qua', else dd/MM/yyyy (local calendar days). */
export function lastUsedLabel(iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((startOfDay(now) - startOfDay(d)) / DAY)
  if (days <= 0) return 'hôm nay'
  if (days === 1) return 'hôm qua'
  return formatDate(d)
}
