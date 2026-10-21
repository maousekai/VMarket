import apiClient from './apiClient'

export const getMyProfile = async () => (await apiClient.get('/api/users/me')).data

export const updateMyProfile = async (profile, idempotencyKey) =>
  (
    await apiClient.put('/api/users/me', profile, {
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  ).data

// PUT replaces the entire profile: keep every field that this form does not edit.
export function profileUpdateBody(current, changes) {
  return {
    fullName: changes.fullName?.trim() || null,
    phone: changes.phone?.trim() || null,
    avatarUrl: current.avatarUrl ?? null,
    dateOfBirth: current.dateOfBirth ?? null,
    gender: current.gender ?? null,
  }
}

export function newIdempotencyKey() {
  return (
    globalThis.crypto.randomUUID?.() ||
    Array.from(globalThis.crypto.getRandomValues(new Uint8Array(16)), (byte) =>
      byte.toString(16).padStart(2, '0'),
    ).join('')
  )
}

// Idempotency-Key is optional on the server. A retry of the same user action must reuse its key,
// while a new action needs a fresh one.
const keyed = (idempotencyKey) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

const addressesUrl = '/api/users/me/addresses'
const addressUrl = (addressId) => `${addressesUrl}/${encodeURIComponent(addressId)}`

export const listMyAddresses = async () => (await apiClient.get(addressesUrl)).data

export const createAddress = async (address, idempotencyKey) =>
  (await apiClient.post(addressesUrl, address, keyed(idempotencyKey))).data

export const updateAddress = async (addressId, address, idempotencyKey) =>
  (await apiClient.put(addressUrl(addressId), address, keyed(idempotencyKey))).data

export const deleteAddress = async (addressId, idempotencyKey) => {
  await apiClient.delete(addressUrl(addressId), keyed(idempotencyKey))
}

export const setDefaultAddress = async (addressId, idempotencyKey) =>
  (await apiClient.put(`${addressUrl(addressId)}/default`, undefined, keyed(idempotencyKey))).data

// The default flag is not part of the body: it is set through a separate endpoint.
export function addressRequestBody(values) {
  return {
    recipientName: values.recipientName?.trim() || '',
    phone: values.phone?.trim() || '',
    province: values.province?.trim() || '',
    district: values.district?.trim() || '',
    ward: values.ward?.trim() || '',
    streetAddress: values.streetAddress?.trim() || '',
    note: values.note?.trim() || null,
  }
}

// No Idempotency-Key here on purpose: a 504 PASSWORD_CHANGE_UNKNOWN must never be resent.
export const changeMyPassword = async ({ currentPassword, newPassword }) =>
  (await apiClient.put('/api/users/me/password', { currentPassword, newPassword })).data

const userUrl = (userId) => `/api/users/${encodeURIComponent(userId)}`

export const searchUsers = async ({ q, status, page = 0, size = 20 } = {}) =>
  (
    await apiClient.get('/api/users', {
      params: { q: q?.trim() || undefined, status: status || undefined, page, size },
    })
  ).data

export const getUser = async (userId) => (await apiClient.get(userUrl(userId))).data

export const listUserActivities = async (userId, { page = 0, size = 20 } = {}) =>
  (await apiClient.get(`${userUrl(userId)}/activities`, { params: { page, size } })).data

export const lockUser = async (userId, reason, idempotencyKey) =>
  (await apiClient.put(`${userUrl(userId)}/lock`, { reason: reason.trim() }, keyed(idempotencyKey)))
    .data

export const unlockUser = async (userId, idempotencyKey) =>
  (await apiClient.put(`${userUrl(userId)}/unlock`, undefined, keyed(idempotencyKey))).data
