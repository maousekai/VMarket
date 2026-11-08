const pad = (n: number) => String(n).padStart(2, '0')

type DateInput = string | number | Date | null | undefined

function toDate(value: DateInput): Date | null {
  if (value === null || value === undefined || value === '') return null
  const d = value instanceof Date ? value : new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}

/** 135000 -> '135.000đ' */
export function formatVnd(amount: number | string | null | undefined): string {
  const n = Math.round(Number(amount ?? 0))
  if (!Number.isFinite(n)) return '0đ'
  const sign = n < 0 ? '-' : ''
  return `${sign}${String(Math.abs(n)).replace(/\B(?=(\d{3})+(?!\d))/g, '.')}đ`
}

/** '0912345678' / '+84912345678' -> '0912 345 678' (unknown shapes are returned as-is). */
export function formatPhone(phone: string | null | undefined): string {
  if (!phone) return ''
  const digits = phone.replace(/[\s.-]/g, '')
  const local = digits.startsWith('+84') ? `0${digits.slice(3)}` : digits
  return /^0\d{9}$/.test(local)
    ? `${local.slice(0, 4)} ${local.slice(4, 7)} ${local.slice(7)}`
    : phone
}

/** Strip spaces/dots/dashes as the backend expects. */
export function normalizePhone(phone: string): string {
  return phone.replace(/[\s.-]/g, '')
}

/** '28/09/2026' */
export function formatDate(value: DateInput): string {
  const d = toDate(value)
  return d ? `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}` : ''
}

/** '14:45' */
export function formatTime(value: DateInput): string {
  const d = toDate(value)
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : ''
}

/** '28/09/2026 · 14:45' */
export function formatDateTime(value: DateInput): string {
  const d = toDate(value)
  return d ? `${formatDate(d)} · ${formatTime(d)}` : ''
}

/** 'vừa xong', '5 phút trước', '3 giờ trước', '2 ngày trước', else dd/MM/yyyy. */
export function formatRelative(value: DateInput, now: number = Date.now()): string {
  const d = toDate(value)
  if (!d) return ''
  const sec = Math.round((now - d.getTime()) / 1000)
  if (sec < 45) return 'vừa xong'
  if (sec < 3600) return `${Math.max(1, Math.round(sec / 60))} phút trước`
  if (sec < 86400) return `${Math.round(sec / 3600)} giờ trước`
  if (sec < 86400 * 7) return `${Math.round(sec / 86400)} ngày trước`
  return formatDate(d)
}

/** Seconds -> 'mm:ss' (countdowns). */
export function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds))
  return `${pad(Math.floor(s / 60))}:${pad(s % 60)}`
}
