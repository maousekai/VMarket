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
