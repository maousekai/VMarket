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

export const passwordRequirements = (password = '') => [
  {
    key: 'length',
    label: 'Từ 8 đến 32 ký tự',
    met: password.length >= 8 && password.length <= 32,
  },
  {
    key: 'uppercase',
    label: 'Có ít nhất 1 chữ hoa',
    met: /[A-Z]/.test(password),
  },
  {
    key: 'digit',
    label: 'Có ít nhất 1 chữ số',
    met: /\d/.test(password),
  },
  {
    key: 'special',
    label: 'Có ít nhất 1 ký tự đặc biệt',
    met: /[^A-Za-z0-9]/.test(password),
  },
]

const compact = (errors) => Object.fromEntries(Object.entries(errors).filter(([, value]) => value))

export const validateLogin = ({ email, password }) =>
  compact({
    email: emailError(email),
    password: password?.trim() ? undefined : 'Vui lòng nhập mật khẩu.',
  })

const genders = ['MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED']

const vnDate = new Intl.DateTimeFormat('en-US', {
  timeZone: 'Asia/Ho_Chi_Minh',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

// The product is Vietnam-only: the birth-date boundary must not move with the browser's timezone.
function dateInVietnam(date) {
  const parts = Object.fromEntries(
    vnDate
      .formatToParts(date)
      .filter((part) => part.type !== 'literal')
      .map((part) => [part.type, part.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}`
}

function dateOfBirthError(value, today) {
  if (!value) return undefined
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  const date = match && new Date(Date.UTC(+match[1], +match[2] - 1, +match[3]))
  if (!date || date.toISOString().slice(0, 10) !== value) return 'Ngày sinh không hợp lệ.'
  if (value >= dateInVietnam(today)) return 'Ngày sinh phải ở quá khứ.'
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
    province: values.province?.trim() ? undefined : 'Vui lòng chọn tỉnh/thành phố.',
    ward: values.ward?.trim() ? undefined : 'Vui lòng chọn phường/xã.',
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
