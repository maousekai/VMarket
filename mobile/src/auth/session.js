// Doc claim tu access token (khong xac minh chu ky: viec do la cua backend).

function decodeBase64Url(value) {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
  const binary = globalThis.atob(padded)
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}

export function claimsFromToken(token) {
  try {
    return JSON.parse(decodeBase64Url(String(token).split('.')[1]))
  } catch {
    return null
  }
}

/** Phien dang nhap tu access token; null neu token hong hoac thieu sub. */
export function sessionFromToken(accessToken, fallbackRoles = []) {
  const claims = claimsFromToken(accessToken)
  if (!claims?.sub) return null
  const roles = Array.isArray(claims.roles) && claims.roles.length ? claims.roles : fallbackRoles
  return {
    accessToken,
    userId: claims.sub,
    email: claims.email || '',
    username: claims.username || '',
    roles: roles.map((role) => String(role).replace(/^ROLE_/, '')),
    expiresAt: claims.exp ? claims.exp * 1000 : null,
  }
}

export const isShipper = (session) => Boolean(session?.roles?.includes('SHIPPER'))

/**
 * Lay refresh token tu header Set-Cookie cua /login, /refresh (auth-service chi tra no trong
 * cookie HttpOnly `refresh_token`). React Native tra header nay dang chuoi hoac mang.
 */
export function refreshTokenFromHeaders(headers) {
  const raw = headers?.['set-cookie'] ?? headers?.['Set-Cookie']
  const cookies = Array.isArray(raw) ? raw : raw ? [raw] : []
  for (const header of cookies) {
    for (const part of String(header).split(/,(?=\s*[\w-]+=)/)) {
      const match = /^\s*refresh_token=([^;]*)/.exec(part)
      if (match && match[1]) return decodeURIComponent(match[1])
    }
  }
  return null
}
