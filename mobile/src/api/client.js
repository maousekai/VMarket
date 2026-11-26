import axios from 'axios'
import { API_URL, REQUEST_TIMEOUT_MS } from '../config'
import { clearTokens, loadTokens, saveTokens } from '../auth/tokenStore'
import { refreshTokenFromHeaders } from '../auth/session'

/**
 * Client duy nhat goi API Gateway. Gan access token, het han (401) thi tu refresh dung MOT lan
 * cho moi request dang cho (single-flight) roi phat lai; refresh that bai = dang xuat.
 *
 * auth-service chi nhan refresh token qua cookie `refresh_token`, nen app tu doc Set-Cookie,
 * cat vao SecureStore va gui lai bang header Cookie (khong dua vao cookie jar cua he dieu hanh).
 */
const client = axios.create({ baseURL: API_URL, timeout: REQUEST_TIMEOUT_MS })

let accessToken = null
let refreshing = null
const listeners = new Set()

/** Nghe thay doi phien: (accessToken | null) => void. Tra ham huy dang ky. */
export function onSessionChange(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function emit(token) {
  listeners.forEach((listener) => listener(token))
}

export async function restoreTokens() {
  const tokens = await loadTokens()
  accessToken = tokens.accessToken
  return tokens
}

/** Luu token tu response dang nhap / refresh (body + Set-Cookie). */
export async function storeAuthResponse(response) {
  const refreshToken = refreshTokenFromHeaders(response.headers)
  accessToken = response.data.accessToken
  await saveTokens({ accessToken, refreshToken })
  emit(accessToken)
  return accessToken
}

export async function signOutLocally() {
  accessToken = null
  await clearTokens()
  emit(null)
}

const isAuthPath = (url = '') => /\/api\/auth\/(login|register|refresh|password|otp)/.test(url)

export function refreshAccessToken() {
  if (!refreshing) {
    refreshing = (async () => {
      const { refreshToken } = await loadTokens()
      if (!refreshToken) throw Object.assign(new Error('NO_REFRESH_TOKEN'), { code: 'NO_SESSION' })
      const response = await axios.post(`${API_URL}/api/auth/refresh`, null, {
        timeout: REQUEST_TIMEOUT_MS,
        headers: { Cookie: `refresh_token=${encodeURIComponent(refreshToken)}` },
        withCredentials: false,
      })
      return storeAuthResponse(response)
    })().finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

client.interceptors.request.use(async (config) => {
  if (!accessToken && !isAuthPath(config.url)) await restoreTokens()
  if (accessToken && !isAuthPath(config.url))
    config.headers.set('Authorization', `Bearer ${accessToken}`)
  return config
})

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config
    const code = error.response?.data?.error?.code
    if (code === 'ACCOUNT_SUSPENDED') {
      await signOutLocally()
      return Promise.reject(error)
    }
    if (!config || config._retried || error.response?.status !== 401 || isAuthPath(config.url))
      return Promise.reject(error)
    config._retried = true
    try {
      const token = await refreshAccessToken()
      config.headers.set('Authorization', `Bearer ${token}`)
      return client(config)
    } catch (refreshError) {
      // Refresh token het han / bi thu hoi: buoc dang nhap lai. Loi mang thi giu phien.
      const status = refreshError?.response?.status
      if (status === 401 || status === 403 || refreshError?.code === 'NO_SESSION')
        await signOutLocally()
      return Promise.reject(error)
    }
  },
)

/** {code, status, message, fieldErrors} tu loi axios theo body chuan cua backend. */
export function getApiError(error) {
  const body = error?.response?.data?.error
  const status = error?.response?.status || 0
  return {
    code: body?.code || error?.code || 'NETWORK_ERROR',
    status,
    message:
      body?.message ||
      (status
        ? 'Đã có lỗi xảy ra. Vui lòng thử lại.'
        : 'Không thể kết nối máy chủ. Kiểm tra mạng và thử lại.'),
    fieldErrors: Object.fromEntries(
      (body?.details || []).filter((d) => d.field).map((d) => [d.field, d.message]),
    ),
  }
}

export default client
