import apiClient from './apiClient'
import { newIdempotencyKey } from './userApi'

// Order Service (PBL6-17). Don dat tu TOAN BO gio hang tren server; request chi gui dia chi,
// ghi chu va phuong thuc thanh toan.

export const PAYMENT_METHOD = Object.freeze({ cod: 'COD', payos: 'PAYOS' })

const ordersUrl = '/api/orders'
const orderUrl = (orderId) => `${ordersUrl}/${encodeURIComponent(orderId)}`
const returnUrl = (returnId) => `${ordersUrl}/returns/${encodeURIComponent(returnId)}`

/** Spring Page co the serialize truc tiep ({number,...}) hoac qua DTO ({page:{number,...}}). */
export function normalizePage(data) {
  const meta = data?.page && typeof data.page === 'object' ? data.page : data || {}
  return {
    content: data?.content || [],
    page: meta.number ?? 0,
    size: meta.size ?? data?.content?.length ?? 0,
    totalElements: meta.totalElements ?? data?.content?.length ?? 0,
    totalPages: meta.totalPages ?? 1,
  }
}

const pageParams = ({ status, page = 0, size = 10, ...rest } = {}) => {
  const params = { page, size, ...rest }
  if (status) params.status = status
  return { params }
}

// ---- Buyer ----

export const placeOrder = async ({ addressId, note, paymentMethod }, idempotencyKey) =>
  (
    await apiClient.post(
      ordersUrl,
      { addressId, note: note?.trim() || null, paymentMethod },
      { headers: { 'Idempotency-Key': idempotencyKey || newIdempotencyKey() } },
    )
  ).data

export const listMyOrders = async () => (await apiClient.get(ordersUrl)).data

export const getOrder = async (orderId) => (await apiClient.get(orderUrl(orderId))).data

export const cancelOrder = async (orderId) =>
  (await apiClient.put(`${orderUrl(orderId)}/cancel`)).data

export const completeOrder = async (orderId) =>
  (await apiClient.post(`${orderUrl(orderId)}/complete`)).data

export const createReturn = async (orderId, { reason, evidenceUrls, items }) =>
  (await apiClient.post(`${orderUrl(orderId)}/returns`, { reason, evidenceUrls, items })).data

export const listOrderReturns = async (orderId) =>
  (await apiClient.get(`${orderUrl(orderId)}/returns`)).data

export const listMyReturns = async () => (await apiClient.get(`${ordersUrl}/returns/me`)).data

export const disputeReturn = async (returnId, reason) =>
  (await apiClient.post(`${returnUrl(returnId)}/dispute`, { reason })).data

const UPLOAD_TIMEOUT = 45_000

export async function uploadReturnEvidence(file) {
  const body = new FormData()
  body.append('file', file)
  return (await apiClient.post(`${ordersUrl}/returns/evidence`, body, { timeout: UPLOAD_TIMEOUT }))
    .data
}

export const deleteReturnEvidence = async (url) => {
  await apiClient.delete(`${ordersUrl}/returns/evidence`, { params: { url } })
}

// ---- Seller ----

const sellerUrl = `${ordersUrl}/seller`

export const listSellerOrders = async (query) =>
  normalizePage((await apiClient.get(sellerUrl, pageParams(query))).data)

export const confirmSellerOrder = async (orderId) =>
  (await apiClient.post(`${sellerUrl}/${encodeURIComponent(orderId)}/confirm`)).data

export const listSellerReturns = async (query) =>
  normalizePage((await apiClient.get(`${sellerUrl}/returns`, pageParams(query))).data)

const sellerReturnUrl = (returnId) => `${sellerUrl}/returns/${encodeURIComponent(returnId)}`

export const acceptReturn = async (returnId) =>
  (await apiClient.post(`${sellerReturnUrl(returnId)}/accept`)).data

export const rejectReturn = async (returnId, reason) =>
  (await apiClient.post(`${sellerReturnUrl(returnId)}/reject`, { reason })).data

export const confirmReturnReceipt = async (returnId) =>
  (await apiClient.post(`${sellerReturnUrl(returnId)}/confirm-receipt`)).data
