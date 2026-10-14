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
    return '/'
  let path
  let destination
  try {
    destination = new URL(candidate, 'https://vmarket.local')
    if (destination.origin !== 'https://vmarket.local') return '/'
    path = decodeURIComponent(destination.pathname).replace(/\/+$/, '') || '/'
    if (
      path.startsWith('//') ||
      path.includes('\\') ||
      [...path].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)
    )
      return '/'
  } catch {
    return '/'
  }
  if (
    ['/login', '/register', '/verify-email', '/forgot-password', '/reset-password'].includes(path)
  )
    return '/'
  if (path.startsWith('/admin') && !roles.includes('ADMIN')) return '/'
  if (path.startsWith('/shipper') && !roles.includes('SHIPPER')) return '/'
  return `${destination.pathname}${destination.search}${destination.hash}`
}
