export interface JwtClaims {
  sub?: string
  email?: string
  username?: string
  roles?: string[]
  email_verified?: boolean
  iat?: number
  exp?: number
  [key: string]: unknown
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function base64UrlToBytes(input: string): number[] {
  const s = input.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '')
  const out: number[] = []
  let buffer = 0
  let bits = 0
  for (const ch of s) {
    const v = B64.indexOf(ch)
    if (v < 0) throw new Error('invalid base64')
    buffer = ((buffer << 6) | v) & 0xffffff
    bits += 6
    if (bits >= 8) {
      bits -= 8
      out.push((buffer >> bits) & 0xff)
    }
  }
  return out
}

function utf8Decode(bytes: number[]): string {
  let out = ''
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i++]
    if (b < 0x80) out += String.fromCharCode(b)
    else if (b < 0xe0) out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f))
    else if (b < 0xf0) {
      out += String.fromCharCode(
        ((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f),
      )
    } else {
      const cp =
        ((b & 0x07) << 18) |
        ((bytes[i++] & 0x3f) << 12) |
        ((bytes[i++] & 0x3f) << 6) |
        (bytes[i++] & 0x3f)
      out += String.fromCodePoint(cp)
    }
  }
  return out
}

/** Decode the payload of a JWT without verifying it (no Buffer / atob needed). Null if malformed. */
export function decodeJwt(token: string | null | undefined): JwtClaims | null {
  if (!token) return null
  const part = token.split('.')[1]
  if (!part) return null
  try {
    return JSON.parse(utf8Decode(base64UrlToBytes(part))) as JwtClaims
  } catch {
    return null
  }
}
