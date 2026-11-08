import {
  create as createAxios,
  type AxiosError,
  type AxiosRequestConfig,
  type AxiosResponse,
} from 'axios'
import { Platform } from 'react-native'

import { AuthStore, type TokenResponse } from '@/auth/session'
import { API_BASE_URL } from '@/lib/config'
import { uuid } from '@/lib/uuid'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Do not attach Authorization and do not try to refresh on 401. */
    skipAuth?: boolean
    /** internal */
    authRetried?: boolean
    authGeneration?: number
    authToken?: string | null
  }
}

const isWeb = Platform.OS === 'web'

/**
 * Refresh delivery ("Option B" of api-auth-user.md section 3):
 * - native: refresh token lives in the JSON body / X-Refresh-Token header (stored in SecureStore).
 * - web preview: browsers send Origin so the server only uses the HttpOnly cookie -> withCredentials.
 */
const baseConfig: AxiosRequestConfig = {
  baseURL: API_BASE_URL,
  timeout: 15000,
  withCredentials: isWeb,
  headers: {
    'Content-Type': 'application/json',
    // Not sent on web: Origin is present there so the server ignores it, and a custom header
    // would only add a CORS preflight requirement.
    ...(isWeb ? {} : { 'X-Client-Type': 'mobile' }),
  },
}

/** Authenticated client for all API calls. */
export const api = createAxios(baseConfig)
/** Interceptor-free client used by the refresh call itself. */
const rawClient = createAxios(baseConfig)

export function newIdempotencyKey(): string {
  return uuid()
}

export interface ApiError {
  code: string
  status: number
  message: string
  fieldErrors: Record<string, string>
  retryable: boolean
}

/** Normalise any thrown value into the same shape as the web's getApiError. */
export function getApiError(error: unknown): ApiError {
  const err = error as AxiosError<{
    error?: { code?: string; message?: string; details?: unknown }
  }>
  const body = err?.response?.data?.error
  const status = err?.response?.status || 0
  const code = body?.code || err?.code || 'NETWORK_ERROR'
  const details = Array.isArray(body?.details)
    ? (body.details as { field?: string; message?: string }[])
    : []
  return {
    code,
    status,
    message:
      body?.message ||
      (status
        ? err?.message || 'Đã có lỗi xảy ra. Vui lòng thử lại.'
        : 'Không thể kết nối máy chủ. Kiểm tra kết nối và thử lại.'),
    fieldErrors: Object.fromEntries(
      details.filter((d) => d.field).map((d) => [d.field as string, d.message ?? '']),
    ),
    retryable: !status || status >= 500 || code === 'REFRESH_TOKEN_ROTATION_CONFLICT',
  }
}

const TERMINAL_REFRESH_CODES = new Set([
  'REFRESH_TOKEN_MISSING',
  'INVALID_REFRESH_TOKEN',
  'REFRESH_TOKEN_EXPIRED',
  'REFRESH_TOKEN_REUSED',
  'REFRESH_TOKEN_REVOKED',
  'SESSION_NOT_FOUND',
  'ACCOUNT_SUSPENDED',
])

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Headers that carry the refresh token for /refresh, /logout, /sessions* on native. */
export function refreshHeaders(): Record<string, string> {
  const token = AuthStore.getRefreshToken()
  return !isWeb && token ? { 'X-Refresh-Token': token } : {}
}

async function doRefresh(attempt: number): Promise<boolean> {
  const token = AuthStore.getRefreshToken()
  if (!isWeb && !token) {
    AuthStore.setGuest()
    return false
  }
  // Identity / clear generation and token at request time: if either changed (logout, another
  // account signed in) by the time the answer arrives, the answer belongs to a dead session.
  const generation = AuthStore.getGeneration()
  const stale = () =>
    AuthStore.getGeneration() !== generation || AuthStore.getRefreshToken() !== token
  try {
    const res = await rawClient.post<TokenResponse>(
      '/api/auth/refresh',
      !isWeb && token ? { refreshToken: token } : undefined,
      { headers: refreshHeaders() },
    )
    if (stale()) return AuthStore.getSnapshot().phase === 'authenticated'
    AuthStore.apply(res.data)
    // The server already rotated: the old token only survives a 30s grace, so the new one must be
    // on disk before this refresh counts as done (an app kill right after would replay the old one).
    await AuthStore.whenPersisted()
    return AuthStore.getSnapshot().phase === 'authenticated'
  } catch (e) {
    if (stale()) return AuthStore.getSnapshot().phase === 'authenticated'
    const err = getApiError(e)
    if (err.code === 'REFRESH_TOKEN_ROTATION_CONFLICT' && attempt === 0) {
      await sleep(150)
      return doRefresh(1)
    }
    if (TERMINAL_REFRESH_CODES.has(err.code)) AuthStore.clear('expired')
    else AuthStore.setGuest() // offline / 5xx: keep stored token, retry later
    return false
  }
}

let inflight: Promise<boolean> | null = null

/** Single-flight session refresh. Resolves true when a fresh access token is in the store. */
export function refreshSession(): Promise<boolean> {
  if (!inflight) {
    inflight = doRefresh(0).finally(() => {
      inflight = null
    })
  }
  return inflight
}

api.interceptors.request.use((config) => {
  if (!config.skipAuth) {
    const token = AuthStore.getAccessToken()
    if (token) config.headers.set('Authorization', `Bearer ${token}`)
    config.authToken = token
    config.authGeneration = AuthStore.getGeneration()
  }
  return config
})

api.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError<{ error?: { code?: string } }>) => {
    const config = error.config
    if (!config || config.skipAuth) return Promise.reject(error)
    const sameSession = config.authGeneration === AuthStore.getGeneration()

    if (error.response?.data?.error?.code === 'ACCOUNT_SUSPENDED' && sameSession) {
      AuthStore.clear()
      return Promise.reject(error)
    }
    if (
      error.response?.status !== 401 ||
      config.authRetried ||
      !config.authToken || // guest request: nothing to refresh
      !sameSession
    ) {
      return Promise.reject(error)
    }
    config.authRetried = true
    // Another request may already have refreshed while this one was in flight.
    if (config.authToken === AuthStore.getAccessToken()) await refreshSession()
    if (AuthStore.getSnapshot().phase !== 'authenticated') return Promise.reject(error)
    config.headers.set('Authorization', `Bearer ${AuthStore.getAccessToken()}`)
    config.authToken = AuthStore.getAccessToken()
    return api(config)
  },
)

export default api
