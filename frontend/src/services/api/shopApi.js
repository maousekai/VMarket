import apiClient from './apiClient'

export const getMyShop = async () => (await apiClient.get('/api/shops/me')).data
