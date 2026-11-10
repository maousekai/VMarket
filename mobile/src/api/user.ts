import { api } from '@/api/client'

export type Gender = 'MALE' | 'FEMALE' | 'OTHER' | 'UNDISCLOSED'

export interface Profile {
  userId: string
  fullName: string | null
  avatarUrl: string | null
  phone: string | null
  dateOfBirth: string | null
  gender: Gender | null
  createdAt: string
  updatedAt: string
}

/** PUT /api/users/me replaces the whole profile: every field must be sent. */
export interface ProfileBody {
  fullName: string | null
  avatarUrl: string | null
  phone: string | null
  dateOfBirth: string | null
  gender: Gender | null
}

export interface Address {
  id: string
  recipientName: string
  phone: string
  province: string
  district: string | null
  ward: string
  streetAddress: string
  note: string | null
  isDefault: boolean
  createdAt: string
  updatedAt: string
}

/** The default flag is not part of the body (PUT .../default). Da Nang has no district level. */
export interface AddressBody {
  recipientName: string
  phone: string
  province: string
  ward: string
  streetAddress: string
  note: string | null
}

const keyed = (key: string) => ({ headers: { 'Idempotency-Key': key } })
const addressesUrl = '/api/users/me/addresses'
const addressUrl = (id: string) => `${addressesUrl}/${encodeURIComponent(id)}`

export async function getMyProfile() {
  return (await api.get<Profile>('/api/users/me')).data
}

export async function updateMyProfile(body: ProfileBody, idempotencyKey: string) {
  return (await api.put<Profile>('/api/users/me', body, keyed(idempotencyKey))).data
}

/** Keeps every field that the caller does not edit (the backend erases omitted ones). */
export function profileBody(current: Profile, changes: Partial<ProfileBody>): ProfileBody {
  return {
    fullName: current.fullName,
    avatarUrl: current.avatarUrl,
    phone: current.phone,
    dateOfBirth: current.dateOfBirth,
    gender: current.gender,
    ...changes,
  }
}

export async function listAddresses() {
  return (await api.get<Address[]>(addressesUrl)).data
}

export async function createAddress(body: AddressBody, idempotencyKey: string) {
  return (await api.post<Address>(addressesUrl, body, keyed(idempotencyKey))).data
}

export async function updateAddress(id: string, body: AddressBody, idempotencyKey: string) {
  return (await api.put<Address>(addressUrl(id), body, keyed(idempotencyKey))).data
}

export async function deleteAddress(id: string, idempotencyKey: string) {
  await api.delete(addressUrl(id), keyed(idempotencyKey))
}

export async function setDefaultAddress(id: string, idempotencyKey: string) {
  return (await api.put<Address>(`${addressUrl(id)}/default`, undefined, keyed(idempotencyKey)))
    .data
}

/** No Idempotency-Key on purpose: a 504 PASSWORD_CHANGE_UNKNOWN must never be resent. */
export async function changePassword(body: { currentPassword: string; newPassword: string }) {
  return (await api.put<{ message: string }>('/api/users/me/password', body)).data
}
