import { api, newIdempotencyKey, refreshHeaders } from '@/api/client'
import type { TokenResponse } from '@/auth/session'

/**
 * Raw auth-service endpoints (gateway paths). These do NOT touch the AuthStore:
 * use `useAuth()` for login/logout/otp so the session state stays consistent.
 * Everything here is public at the gateway, hence skipAuth.
 */

export interface RegisterRequest {
  email: string
  username: string
  password: string
}

export interface RegisterResponse {
  id: string
  email: string
  username: string
  status: 'PENDING' | 'ACTIVE'
  roles: string[]
  createdAt: string
}

export interface OtpRequestResponse {
  expiresInSeconds: number
  message: string
}

export interface SessionSummary {
  id: string
  userAgent: string | null
  ipAddress: string | null
  createdAt: string
  lastUsedAt: string
  current: boolean
}

const pub = { skipAuth: true } as const

export async function register(body: RegisterRequest) {
  const res = await api.post<RegisterResponse>('/api/auth/register', body, {
    ...pub,
    headers: { 'Idempotency-Key': newIdempotencyKey() },
  })
  return res.data
}

export async function login(email: string, password: string) {
  const res = await api.post<TokenResponse>('/api/auth/login', { email, password }, pub)
  return res.data
}

export async function requestOtp(email: string) {
  const res = await api.post<OtpRequestResponse>('/api/auth/otp/request', { email }, pub)
  return res.data
}

export async function verifyOtp(email: string, otp: string) {
  const res = await api.post<TokenResponse>('/api/auth/otp/verify', { email, otp }, pub)
  return res.data
}

export async function logout() {
  const res = await api.post<{ message: string }>(
    '/api/auth/logout',
    { refreshToken: refreshHeaders()['X-Refresh-Token'] },
    { ...pub, headers: refreshHeaders() },
  )
  return res.data
}

export async function forgotPassword(email: string) {
  const res = await api.post<OtpRequestResponse>('/api/auth/password/forgot', { email }, pub)
  return res.data
}

export async function resetPassword(email: string, code: string, newPassword: string) {
  const res = await api.post<{ message: string }>(
    '/api/auth/password/reset',
    { email, code, newPassword },
    pub,
  )
  return res.data
}

export async function listSessions() {
  const res = await api.get<SessionSummary[]>('/api/auth/sessions', {
    ...pub,
    headers: refreshHeaders(),
  })
  return res.data
}

export async function revokeSession(id: string) {
  const res = await api.delete<{ message: string }>(`/api/auth/sessions/${id}`, {
    ...pub,
    headers: refreshHeaders(),
  })
  return res.data
}

export async function revokeOtherSessions() {
  const res = await api.post<{ message: string }>('/api/auth/sessions/revoke-others', undefined, {
    ...pub,
    headers: refreshHeaders(),
  })
  return res.data
}
