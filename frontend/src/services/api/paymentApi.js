import apiClient from './apiClient'

// Payment Service (PBL6-19): COD tao ban ghi COD_PENDING; PayOS tra checkoutUrl + qrCode khi don da
// WAITING_PAYMENT va da giu hang (stockReserved).
const baseUrl = '/api/payments'
const paymentUrl = (paymentId) => `${baseUrl}/${encodeURIComponent(paymentId)}`

/** Tao (hoac lay lai) giao dich cua don; goi nhieu lan tra cung mot link PayOS. */
export const createPayment = async (orderId) => (await apiClient.post(baseUrl, { orderId })).data

export const getPayment = async (paymentId) => (await apiClient.get(paymentUrl(paymentId))).data

/** Giao dich moi nhat truoc; API tra mang (khong co tong so). */
export const listMyPayments = async ({ page = 0, size = 20 } = {}) =>
  (await apiClient.get(baseUrl, { params: { page, size } })).data

export const getPaymentByOrder = async (orderId) =>
  (await apiClient.get(`${baseUrl}/orders/${encodeURIComponent(orderId)}`)).data

export const listPaymentRefunds = async (paymentId) =>
  (await apiClient.get(`${paymentUrl(paymentId)}/refunds`)).data

/** Hoi PayOS trang thai giao dich (khong can webhook o moi truong local). */
export const syncPayment = async (paymentId) =>
  (await apiClient.post(`${paymentUrl(paymentId)}/sync`)).data

// ---- Admin ----

export const listPendingRefunds = async ({ page = 0, size = 20 } = {}) =>
  (await apiClient.get(`${baseUrl}/admin/refunds`, { params: { page, size } })).data

export const listReconciliations = async ({ page = 0, size = 20 } = {}) =>
  (await apiClient.get(`${baseUrl}/admin/reconciliations`, { params: { page, size } })).data

/** Admin ghi nhan da chuyen khoan hoan tien (ma giao dich ngan hang). */
export const confirmRefund = async (refundId, transferReference) =>
  (
    await apiClient.put(`${baseUrl}/admin/refunds/${encodeURIComponent(refundId)}/confirm`, {
      transferReference,
    })
  ).data
