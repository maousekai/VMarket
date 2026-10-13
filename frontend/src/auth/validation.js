const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const usernamePattern = /^[a-zA-Z0-9._-]{3,50}$/
const passwordPattern = /^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/
const phonePattern = /^(0\d{9}|\+84\d{9})$/

function emailError(email) {
  if (!email?.trim()) return 'Vui lòng nhập email.'
  if (email.trim().length > 320 || !emailPattern.test(email.trim()))
    return 'Email không đúng định dạng.'
}

function passwordError(password) {
  if (!passwordPattern.test(password || ''))
    return 'Mật khẩu phải 8–32 ký tự, có chữ hoa, số và ký tự đặc biệt.'
}

const compact = (errors) => Object.fromEntries(Object.entries(errors).filter(([, value]) => value))

export const validateLogin = ({ identifier, password }) =>
  compact({
    identifier: !identifier?.trim()
      ? 'Vui lòng nhập tên tài khoản hoặc email.'
      : identifier.trim().length > 320
        ? 'Tên tài khoản hoặc email tối đa 320 ký tự.'
        : undefined,
    password: password?.trim() ? undefined : 'Vui lòng nhập mật khẩu.',
  })

export function validateProfile({ fullName, phone }) {
  return compact({
    fullName: fullName?.trim().length > 100 ? 'Họ tên tối đa 100 ký tự.' : undefined,
    phone:
      phone?.trim() && !phonePattern.test(phone.trim())
        ? 'Số điện thoại phải là số di động Việt Nam hợp lệ.'
        : undefined,
  })
}

export function validateRegistration(values) {
  return compact({
    ...validateProfile(values),
    email: emailError(values.email),
    username: !usernamePattern.test(values.username?.trim() || '')
      ? 'Tên tài khoản 3–50 ký tự, chỉ gồm chữ, số và . _ -.'
      : undefined,
    password: passwordError(values.password),
    confirmPassword:
      values.confirmPassword !== values.password ? 'Mật khẩu xác nhận không khớp.' : undefined,
  })
}

export const validateOtp = ({ email, otp }) =>
  compact({
    email: emailError(email),
    otp: /^\d{6}$/.test(otp || '') ? undefined : 'Mã gồm 6 chữ số.',
  })

export const validateForgotPassword = ({ email }) => compact({ email: emailError(email) })

export const validateResetPassword = ({ email, code, newPassword, confirmPassword }) =>
  compact({
    email: emailError(email),
    code: /^\d{6}$/.test(code || '') ? undefined : 'Mã gồm 6 chữ số.',
    newPassword: passwordError(newPassword),
    confirmPassword: confirmPassword !== newPassword ? 'Mật khẩu xác nhận không khớp.' : undefined,
  })
