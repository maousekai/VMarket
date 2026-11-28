export function safeReturnTo(candidate, roles = []) {
  if (
    typeof candidate !== 'string' ||
    !candidate.startsWith('/') ||
    candidate.startsWith('//') ||
    candidate.includes('\\') ||
    [...candidate].some(
      (character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
    )
  )
    return home(roles)
  let path
  let destination
  try {
    destination = new URL(candidate, 'https://vmarket.local')
    if (destination.origin !== 'https://vmarket.local') return home(roles)
    path = decodeURIComponent(destination.pathname).replace(/\/+$/, '') || '/'
    if (
      path.startsWith('//') ||
      path.includes('\\') ||
      [...path].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)
    )
      return home(roles)
  } catch {
    return home(roles)
  }
  if (
    ['/login', '/register', '/verify-email', '/forgot-password', '/reset-password'].includes(path)
  )
    return home(roles)
  if (path.startsWith('/admin') && !roles.includes('ADMIN')) return home(roles)
  if (path.startsWith('/shipper') && !roles.includes('SHIPPER')) return home(roles)
  if (path === '/' && !destination.search && !destination.hash) return home(roles)
  return `${destination.pathname}${destination.search}${destination.hash}`
}

/** Tai khoan chi lam shipper (khong quan tri / ban hang) vao thang khu vuc giao hang. */
export const isShipperOnly = (roles = []) =>
  roles.includes('SHIPPER') && !roles.includes('ADMIN') && !roles.includes('SELLER')

export const home = (roles = []) => (isShipperOnly(roles) ? '/shipper' : '/')
