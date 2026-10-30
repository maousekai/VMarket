export function normalizePhone(phone) {
  return String(phone ?? '').replace(/[\s.-]/g, '')
}
