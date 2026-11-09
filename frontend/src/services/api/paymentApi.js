import apiClient from './apiClient'

// Payment Service (PBL6-19): COD tao ban ghi COD_PENDING; PayOS tra checkoutUrl khi don da
// WAITING_PAYMENT va da giu hang (stockReserved).
export const createPayment = async (orderId) =>
  (await apiClient.post('/api/payments', { orderId })).data

export const getPayment = async (paymentId) =>
  (await apiClient.get(`/api/payments/${encodeURIComponent(paymentId)}`)).data
