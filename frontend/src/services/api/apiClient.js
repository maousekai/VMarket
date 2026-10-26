import axios from 'axios'

// baseURL rong = goi duong dan tuong doi (/api/...) ve chinh origin dang mo:
//   - dev  (npm run dev): vite proxy /api -> API Gateway (vite.config.js)
//   - docker (nginx)    : nginx reverse proxy /api -> API Gateway
// Ca hai deu cung origin nen khong dinh CORS/preflight.
// Chi dat VITE_API_BASE_URL khi co chu dinh goi thang Gateway o origin khac
// (luc do BE phai cho phep origin cua FE trong CORS_ALLOWED_ORIGINS).
const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
})

let sessionManager

export function bindSessionManager(manager) {
  sessionManager = manager
}

function isAuthRequest(config) {
  return /\/api\/auth(?:\/|$)/.test(config.url || '')
}

apiClient.interceptors.request.use((config) => {
  if (config.skipAuth || isAuthRequest(config)) return config
  const session = sessionManager?.getSnapshot().session
  if (session) config.headers.set('Authorization', `Bearer ${session.accessToken}`)
  config.authGeneration = sessionManager?.getGeneration()
  config.authToken = session?.accessToken
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config
    const session = sessionManager?.getSnapshot().session
    if (
      config &&
      !config.skipAuth &&
      !isAuthRequest(config) &&
      error.response?.data?.error?.code === 'ACCOUNT_SUSPENDED' &&
      config.authGeneration === sessionManager?.getGeneration()
    ) {
      sessionManager.invalidateSession()
      return Promise.reject(error)
    }
    if (
      !config ||
      config.skipAuth ||
      isAuthRequest(config) ||
      config.authRetried ||
      error.response?.status !== 401 ||
      !session ||
      config.authGeneration !== sessionManager.getGeneration()
    ) {
      return Promise.reject(error)
    }
    config.authRetried = true
    // Another request may already have refreshed while this request was in flight.
    if (config.authToken === session.accessToken) await sessionManager.refresh()
    if (
      !sessionManager.getSnapshot().session ||
      config.authGeneration !== sessionManager.getGeneration()
    ) {
      return Promise.reject(error)
    }
    return apiClient(config)
  },
)

export function getApiError(error) {
  const body = error.response?.data?.error
  const status = error.response?.status || error.status || 0
  const code = body?.code || error.code || 'NETWORK_ERROR'
  return {
    code,
    status,
    message:
      body?.message ||
      (status
        ? error.message || 'Đã có lỗi xảy ra. Vui lòng thử lại.'
        : 'Không thể kết nối máy chủ. Kiểm tra kết nối và thử lại.'),
    fieldErrors: Object.fromEntries(
      (body?.details || [])
        .filter((detail) => detail.field)
        .map(({ field, message }) => [field, message]),
    ),
    retryable: !status || status >= 500 || code === 'REFRESH_TOKEN_ROTATION_CONFLICT',
  }
}

export default apiClient
