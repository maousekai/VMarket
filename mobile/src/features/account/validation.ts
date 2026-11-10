import type { AddressBody, Gender, ProfileBody } from '@/api/user'
import { normalizePhone } from '@/lib/format'

import { dateOfBirthError, displayToIso } from './dates'

export type FieldErrors<K extends string = string> = Partial<Record<K, string>>

// Mirrors the backend bean validation (UpdateProfileRequest / AddressRequest / register policy).
const PHONE_PATTERN = /^(0\d{9}|\+84\d{9})$/
const PASSWORD_PATTERN = /^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,32}$/

export const PASSWORD_HINT = '8–32 ký tự, có chữ hoa, số và ký tự đặc biệt.'

export const GENDER_OPTIONS: { value: Gender; label: string }[] = [
  { value: 'MALE', label: 'Nam' },
  { value: 'FEMALE', label: 'Nữ' },
  { value: 'OTHER', label: 'Khác' },
  { value: 'UNDISCLOSED', label: 'Không muốn tiết lộ' },
]

export const genderLabel = (gender: Gender | null | undefined) =>
  GENDER_OPTIONS.find((o) => o.value === gender)?.label ?? ''

const isValidPhone = (phone: string) => PHONE_PATTERN.test(normalizePhone(phone))

function compact<K extends string>(errors: FieldErrors<K>): FieldErrors<K> {
  return Object.fromEntries(Object.entries(errors).filter(([, v]) => v)) as FieldErrors<K>
}

export interface ProfileValues {
  fullName: string
  phone: string
  /** dd/MM/yyyy as typed. */
  dateOfBirth: string
  gender: Gender | null
}

export function validateProfile(values: ProfileValues): FieldErrors<keyof ProfileValues> {
  const phone = values.phone.trim()
  return compact<keyof ProfileValues>({
    fullName: values.fullName.trim().length > 100 ? 'Họ tên tối đa 100 ký tự.' : undefined,
    phone:
      phone && !isValidPhone(phone)
        ? 'Số điện thoại chưa đầy đủ. Kiểm tra và nhập lại.'
        : undefined,
    dateOfBirth: dateOfBirthError(values.dateOfBirth),
  })
}

/** Form values -> PUT body fields (empty values become null, i.e. cleared). */
export function profileChanges(
  values: ProfileValues,
): Pick<ProfileBody, 'fullName' | 'phone' | 'dateOfBirth' | 'gender'> {
  return {
    fullName: values.fullName.trim() || null,
    phone: normalizePhone(values.phone) || null,
    dateOfBirth: displayToIso(values.dateOfBirth),
    gender: values.gender,
  }
}

export interface AddressValues {
  recipientName: string
  phone: string
  streetAddress: string
  ward: string
  province: string
  makeDefault: boolean
}

const required = (value: string, label: string, max: number) => {
  const text = value.trim()
  if (!text) return `Vui lòng nhập ${label}.`
  if (text.length > max) return `Tối đa ${max} ký tự.`
  return undefined
}

export function validateAddress(values: AddressValues): FieldErrors<keyof AddressValues> {
  const phone = values.phone.trim()
  return compact<keyof AddressValues>({
    recipientName: required(values.recipientName, 'tên người nhận', 100),
    phone: !phone
      ? 'Vui lòng nhập số điện thoại.'
      : isValidPhone(phone)
        ? undefined
        : 'Số điện thoại chưa đầy đủ. Kiểm tra và nhập lại.',
    streetAddress: required(values.streetAddress, 'số nhà, tên đường', 255),
    ward: values.ward.trim() ? undefined : 'Vui lòng chọn phường / xã.',
    province: values.province.trim() ? undefined : 'Vui lòng chọn tỉnh / thành.',
  })
}

export function addressBody(values: AddressValues): AddressBody {
  return {
    recipientName: values.recipientName.trim(),
    phone: normalizePhone(values.phone),
    province: values.province.trim(),
    ward: values.ward.trim(),
    streetAddress: values.streetAddress.trim(),
    note: null,
  }
}

export interface PasswordValues {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export function validatePasswordChange(values: PasswordValues): FieldErrors<keyof PasswordValues> {
  const { currentPassword, newPassword, confirmPassword } = values
  return compact<keyof PasswordValues>({
    currentPassword: !currentPassword
      ? 'Vui lòng nhập mật khẩu hiện tại.'
      : currentPassword.length > 72
        ? 'Mật khẩu hiện tại không hợp lệ.'
        : undefined,
    newPassword: !PASSWORD_PATTERN.test(newPassword)
      ? 'Mật khẩu phải 8–32 ký tự, có chữ hoa, số và ký tự đặc biệt.'
      : newPassword === currentPassword
        ? 'Mật khẩu mới phải khác mật khẩu hiện tại.'
        : undefined,
    confirmPassword: confirmPassword !== newPassword ? 'Mật khẩu xác nhận không khớp.' : undefined,
  })
}
