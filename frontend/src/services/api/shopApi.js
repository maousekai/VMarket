import apiClient from './apiClient'

export const getMyShop = async () => (await apiClient.get('/api/shops/me')).data

export const getPublicShop = async (shopId) =>
  (await apiClient.get(`/api/shops/${encodeURIComponent(shopId)}`, { skipAuth: true })).data
