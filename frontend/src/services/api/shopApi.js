import apiClient from './apiClient'

export const getMyShop = async () => (await apiClient.get('/api/shops/me')).data

export const registerShop = async (shop) => (await apiClient.post('/api/shops', shop)).data

export const updateMyShop = async (shop) => (await apiClient.put('/api/shops/me', shop)).data

export const resubmitMyShop = async () => (await apiClient.post('/api/shops/me/resubmit')).data

export const getMyStatusHistory = async () =>
  (await apiClient.get('/api/shops/me/status-history')).data

export const getShopStats = async (range = '7d') =>
  (await apiClient.get('/api/shops/me/stats', { params: { range } })).data

export const getRecentOrders = async (limit = 5) =>
  (await apiClient.get('/api/shops/me/recent-orders', { params: { limit } })).data

export const getPublicShop = async (shopId) =>
  (await apiClient.get(`/api/shops/${encodeURIComponent(shopId)}`, { skipAuth: true })).data

const adminUrl = '/api/shops/admin'

export const getShopCounts = async () => (await apiClient.get(`${adminUrl}/counts`)).data

export const searchAdminShops = async ({ status = '', keyword = '', page = 0, size = 10 } = {}) =>
  (
    await apiClient.get(adminUrl, {
      params: { status: status || undefined, keyword: keyword.trim() || undefined, page, size },
    })
  ).data

export const getAdminShop = async (shopId) =>
  (await apiClient.get(`${adminUrl}/${encodeURIComponent(shopId)}`)).data

export const getAdminShopStatusHistory = async (shopId) =>
  (await apiClient.get(`${adminUrl}/${encodeURIComponent(shopId)}/status-history`)).data

export const getAdminShopProfileHistory = async (shopId, { page = 0, size = 20 } = {}) =>
  (
    await apiClient.get(`${adminUrl}/${encodeURIComponent(shopId)}/profile-history`, {
      params: { page, size },
    })
  ).data

export const approveShop = async (shopId) =>
  (await apiClient.post(`${adminUrl}/${encodeURIComponent(shopId)}/approve`)).data

export const rejectShop = async (shopId, reason) =>
  (await apiClient.post(`${adminUrl}/${encodeURIComponent(shopId)}/reject`, { reason })).data

export const suspendShop = async (shopId, reason) =>
  (await apiClient.post(`${adminUrl}/${encodeURIComponent(shopId)}/suspend`, { reason })).data

export const reinstateShop = async (shopId) =>
  (await apiClient.post(`${adminUrl}/${encodeURIComponent(shopId)}/reinstate`)).data
