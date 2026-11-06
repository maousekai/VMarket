import apiClient from './apiClient'

// Can dang nhap: gateway va recommendation-service doi Bearer token.
export async function getForYou({ limit = 8 } = {}) {
  const { data } = await apiClient.get('/api/ai/recommendations/for-you', { params: { limit } })
  return Array.isArray(data?.items) ? data.items : []
}
