import apiClient from './apiClient'

export const listProducts = async ({ shopId, page = 0, size = 20, sort = 'NEWEST' } = {}) =>
  (
    await apiClient.get('/api/products', {
      params: { shopId, page, size, sort },
    })
  ).data

export const getProduct = async (productId) =>
  (await apiClient.get(`/api/products/${encodeURIComponent(productId)}`)).data
