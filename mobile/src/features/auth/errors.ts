import type { ApiError } from '@/api/client'

/** Minutes left on a login lockout; the backend only sends it inside the message text. */
export function parseLockMinutes(message: string | undefined): number | null {
  const match = /(\d+)\s*phút/.exec(message ?? '')
  return match ? Number(match[1]) : null
}

const OTP_INLINE_CODES = new Set([
  'OTP_INVALID',
  'OTP_TOO_MANY_ATTEMPTS',
  'OTP_ALREADY_USED',
  'OTP_NOT_FOUND',
])

export const isOtpInlineError = (error: ApiError) => OTP_INLINE_CODES.has(error.code)

/** Codes after which the reset code can never work again: the user must request a new one. */
export const RESET_NEEDS_NEW_CODE = new Set([
  'PASSWORD_RESET_EXPIRED',
  'PASSWORD_RESET_ALREADY_USED',
  'PASSWORD_RESET_NOT_FOUND',
  'PASSWORD_RESET_TOO_MANY_ATTEMPTS',
])

export function loginErrorMessage(error: ApiError): string {
  switch (error.code) {
    case 'INVALID_CREDENTIALS':
      return 'Email hoặc mật khẩu không đúng'
    case 'ACCOUNT_SUSPENDED':
      return 'Tài khoản đã bị khóa. Vui lòng liên hệ hỗ trợ VMarket để được giúp đỡ.'
    default:
      return error.message
  }
}

export function registerFieldErrors(error: ApiError): Record<string, string> {
  if (error.code === 'EMAIL_ALREADY_EXISTS') return { email: 'Email đã được sử dụng' }
  if (error.code === 'USERNAME_ALREADY_EXISTS') return { username: 'Tên tài khoản đã được sử dụng' }
  return error.fieldErrors
}
