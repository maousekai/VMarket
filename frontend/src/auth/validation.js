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

export const validateLogin = ({ email, password }) =>
  compact({
    email: emailError(email),
    password: password?.trim() ? undefined : 'Vui lòng nhập mật khẩu.',
  })

const genders = ['MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED']

function dateOfBirthError(value, today) {
  if (!value) return undefined
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  const date = match && new Date(Date.UTC(+match[1], +match[2] - 1, +match[3]))
  if (!date || date.toISOString().slice(0, 10) !== value) return 'Ngày sinh không hợp lệ.'
  const pad = (number) => String(number).padStart(2, '0')
  const todayText = `${today.getFullYear()}-${pad(today.getMonth() + 1)}-${pad(today.getDate())}`
  if (value >= todayText) return 'Ngày sinh phải ở quá khứ.'
}

export function validateProfile({ fullName, phone, dateOfBirth, gender }, today = new Date()) {
  return compact({
    fullName: fullName?.trim().length > 100 ? 'Họ tên tối đa 100 ký tự.' : undefined,
    phone:
      phone?.trim() && !phonePattern.test(phone.trim())
        ? 'Số điện thoại phải là số di động Việt Nam hợp lệ.'
        : undefined,
    dateOfBirth: dateOfBirthError(dateOfBirth, today),
    gender: gender && !genders.includes(gender) ? 'Giới tính không hợp lệ.' : undefined,
  })
}

function required(value, label, max) {
  const text = value?.trim() || ''
  if (!text) return `Vui lòng nhập ${label}.`
  if (text.length > max) {
    return `${label[0].toLocaleUpperCase('vi')}${label.slice(1)} tối đa ${max} ký tự.`
  }
}

export function validateAddress(values) {
  const phone = values.phone?.trim() || ''
  return compact({
    recipientName: required(values.recipientName, 'tên người nhận', 100),
    phone: !phone
      ? 'Vui lòng nhập số điện thoại.'
      : phonePattern.test(phone)
        ? undefined
        : 'Số điện thoại phải là số di động Việt Nam hợp lệ.',
    province: required(values.province, 'tỉnh/thành phố', 100),
    district: required(values.district, 'quận/huyện', 100),
    ward: required(values.ward, 'phường/xã', 100),
    streetAddress: required(values.streetAddress, 'địa chỉ chi tiết', 255),
    note: values.note?.trim().length > 255 ? 'Ghi chú tối đa 255 ký tự.' : undefined,
  })
}

export function validateChangePassword({ currentPassword, newPassword, confirmPassword }) {
  return compact({
    currentPassword: !currentPassword?.trim()
      ? 'Vui lòng nhập mật khẩu hiện tại.'
      : currentPassword.length > 72
        ? 'Mật khẩu hiện tại không hợp lệ.'
        : undefined,
    newPassword:
      passwordError(newPassword) ||
      (newPassword === currentPassword ? 'Mật khẩu mới phải khác mật khẩu hiện tại.' : undefined),
    confirmPassword: confirmPassword !== newPassword ? 'Mật khẩu xác nhận không khớp.' : undefined,
  })
}

export const validateUserSearch = ({ q }) =>
  compact({ q: q?.trim().length > 100 ? 'Từ khoá tối đa 100 ký tự.' : undefined })

export const validateLockReason = ({ reason }) =>
  compact({ reason: required(reason, 'lý do khoá', 500) })

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
