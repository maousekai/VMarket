/** Preset failure reasons (the first two come from the Figma frames). */
export const FAILURE_REASONS = [
  'Không liên hệ được người nhận',
  'Người nhận từ chối nhận hàng',
  'Sai địa chỉ',
  'Người nhận hẹn giao lại',
  'Khác',
] as const

export const OTHER_REASON = 'Khác'
export const REASON_MAX = 255

/** The backend has one `reason` column (<= 255): reason + optional note joined by an em dash. */
export function buildFailureReason(reason: string, note: string): string {
  const n = note.trim()
  const full = n ? `${reason} — ${n}` : reason
  return full.length > REASON_MAX ? full.slice(0, REASON_MAX) : full
}

/** "Không liên hệ được người nhận — x" -> "không liên hệ được người nhận — x". */
export function lowerFirst(text: string): string {
  return text ? text.charAt(0).toLowerCase() + text.slice(1) : text
}
