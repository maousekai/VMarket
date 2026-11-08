const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const USERNAME_PATTERN = /^[a-zA-Z0-9._-]{3,50}$/
const PASSWORD_PATTERN = /^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/
const PHONE_PATTERN = /^(0\d{9}|\+84\d{9})$/
const CODE_PATTERN = /^\d{6}$/

export type FieldErrors<K extends string = string> = Partial<Record<K, string>>

export const PASSWORD_HINT =
  'Mật khẩu phải 8–32 ký tự, có ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt'

export function emailError(email: string): string | undefined {
  const value = email.trim()
  if (!value) return 'Email không được để trống'
  if (value.length > 320) return 'Email tối đa 320 ký tự'
  if (!EMAIL_PATTERN.test(value)) return 'Email không đúng định dạng'
}

export const passwordError = (password: string) =>
  PASSWORD_PATTERN.test(password) ? undefined : PASSWORD_HINT

export const codeError = (code: string, message = 'Mã gồm 6 chữ số') =>
  CODE_PATTERN.test(code) ? undefined : message

const confirmError = (password: string, confirm: string) =>
  password === confirm ? undefined : 'Mật khẩu xác nhận không khớp'

function compact<K extends string>(errors: FieldErrors<K>): FieldErrors<K> {
  return Object.fromEntries(Object.entries(errors).filter(([, v]) => v)) as FieldErrors<K>
}

export const validateLogin = (v: { email: string; password: string }) =>
  compact({
    email: emailError(v.email),
    password: v.password ? undefined : 'Mật khẩu không được để trống',
  })

export interface RegisterValues {
  email: string
  username: string
  fullName: string
  phone: string
  password: string
  confirmPassword: string
}

export const validateRegister = (v: RegisterValues) =>
  compact<keyof RegisterValues>({
    email: emailError(v.email),
    username: USERNAME_PATTERN.test(v.username.trim())
      ? undefined
      : 'Username 3–50 ký tự, chỉ gồm chữ, số và . _ -',
    fullName: v.fullName.trim().length > 100 ? 'Họ tên tối đa 100 ký tự' : undefined,
    phone:
      v.phone && !PHONE_PATTERN.test(v.phone)
        ? 'Số điện thoại không hợp lệ (VD: 0912345678)'
        : undefined,
    password: passwordError(v.password),
    confirmPassword: confirmError(v.password, v.confirmPassword),
  })

export const validateOtp = (otp: string) => codeError(otp, 'Mã OTP gồm 6 chữ số')

export const validateReset = (v: {
  email: string
  code: string
  newPassword: string
  confirmPassword: string
}) =>
  compact({
    email: emailError(v.email),
    code: codeError(v.code),
    newPassword: passwordError(v.newPassword),
    confirmPassword: confirmError(v.newPassword, v.confirmPassword),
  })
