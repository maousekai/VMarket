/** Screens of the (auth) stack, as URL segments (group folders are not part of the URL). */
const AUTH_SEGMENTS = new Set([
  'login',
  'register',
  'verify-email',
  'forgot-password',
  'reset-password',
  'check-email',
  'session-expired',
  'locked',
  'google-cancelled',
])

/** True for any path inside the (auth) group, in either URL or `/(auth)/...` form. */
export function isAuthPath(path: string | null | undefined): boolean {
  if (!path) return false
  const segments = path.split(/[?#]/)[0].split('/').filter(Boolean)
  if (segments[0]?.toLowerCase() === '(auth)') return true
  const first = segments.find((s) => !/^\(.*\)$/.test(s))
  return !!first && AUTH_SEGMENTS.has(first.toLowerCase())
}

/**
 * Accept only in-app absolute paths ('/orders/1'); reject '//host', 'http:', empty, and any
 * (auth)-group path (returning to login/register/... after login would loop).
 */
export function safeReturnTo(value: string | string[] | undefined | null): string | null {
  const v = Array.isArray(value) ? value[0] : value
  if (!v || !v.startsWith('/') || v.startsWith('//') || v.startsWith('/\\')) return null
  if (isAuthPath(v)) return null
  return v
}
