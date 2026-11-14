import apiClient from './apiClient'

// Review Service (PBL6-22). Danh sach + diem tong hop cua san pham / gian hang la cong khai: khong
// gui token de mot phien het han khong lam hong trang san pham cua khach.
const publicRequest = { skipAuth: true }
const UPLOAD_TIMEOUT = 45_000

const reviewsUrl = '/api/reviews'
const enc = encodeURIComponent

// Gioi han cua CreateReviewRequest / SellerReplyRequest / HideReviewRequest phia backend.
export const REVIEW_LIMITS = Object.freeze({
  comment: 2000,
  images: 5,
  reply: 1000,
  hideReason: 500,
  maxPageSize: 50,
})

// RETURNED: don da tra hang / hoan tien — danh gia khong con hien thi (PBL6-35).
export const REVIEW_STATUS = Object.freeze({
  visible: 'VISIBLE',
  hidden: 'HIDDEN',
  returned: 'RETURNED',
})

const pageParams = ({ rating, page = 0, size = 10 } = {}) => {
  const params = { page, size }
  if (rating) params.rating = rating
  return params
}

// ---- Cong khai ----

export const getProductReviews = async (productId, query) =>
  (
    await apiClient.get(`${reviewsUrl}/products/${enc(productId)}`, {
      ...publicRequest,
      params: pageParams(query),
    })
  ).data

/** {targetId, average, count, distribution: {"1": n, ..., "5": n}} */
export const getProductRatingSummary = async (productId) =>
  (await apiClient.get(`${reviewsUrl}/products/${enc(productId)}/summary`, publicRequest)).data

export const getShopRatingSummary = async (shopId) =>
  (await apiClient.get(`${reviewsUrl}/shops/${enc(shopId)}/summary`, publicRequest)).data

// ---- Nguoi mua ----

/** Danh gia cua toi; truyen orderId de biet san pham nao trong don da duoc danh gia. */
export const listMyReviews = async ({ orderId, page = 0, size = REVIEW_LIMITS.maxPageSize } = {}) =>
  (await apiClient.get(`${reviewsUrl}/me`, { params: { orderId, page, size } })).data

/** 409 REVIEW_ALREADY_EXISTS khi san pham trong don da duoc danh gia. */
export const createReview = async ({ orderId, productId, rating, comment, imageUrls }) =>
  (
    await apiClient.post(reviewsUrl, {
      orderId,
      productId,
      rating,
      comment: comment?.trim() || null,
      imageUrls,
    })
  ).data

export async function uploadReviewImage(file) {
  const body = new FormData()
  body.append('file', file)
  // No Content-Type override: the browser must add the multipart boundary itself.
  return (await apiClient.post(`${reviewsUrl}/media`, body, { timeout: UPLOAD_TIMEOUT })).data
}

/** Xoa anh da tai len nhung chua gui kem danh gia nao (best-effort khi huy). */
export const deleteReviewImage = async (url) => {
  await apiClient.delete(`${reviewsUrl}/media`, { params: { url } })
}

// ---- Seller ----

/** `replied`: true = da phan hoi, false = chua phan hoi, bo trong = tat ca. */
export const listSellerReviews = async ({ rating, replied, page = 0, size = 10 } = {}) => {
  const params = pageParams({ rating, page, size })
  if (replied !== undefined && replied !== null) params.replied = replied
  return (await apiClient.get(`${reviewsUrl}/seller`, { params })).data
}

/** Tao moi hoac sua phan hoi (moi danh gia toi da mot phan hoi). */
export const replyToReview = async (reviewId, content) =>
  (await apiClient.put(`${reviewsUrl}/seller/${enc(reviewId)}/reply`, { content: content.trim() }))
    .data

export const deleteReviewReply = async (reviewId) => {
  await apiClient.delete(`${reviewsUrl}/seller/${enc(reviewId)}/reply`)
}

// ---- Admin ----

export const searchReviews = async ({ status, productId, shopId, page = 0, size = 20 } = {}) => {
  const params = { page, size }
  if (status) params.status = status
  if (productId?.trim()) params.productId = productId.trim()
  if (shopId?.trim()) params.shopId = shopId.trim()
  return (await apiClient.get(`${reviewsUrl}/admin`, { params })).data
}

export const hideReview = async (reviewId, reason) =>
  (await apiClient.post(`${reviewsUrl}/admin/${enc(reviewId)}/hide`, { reason: reason.trim() }))
    .data

export const restoreReview = async (reviewId) =>
  (await apiClient.post(`${reviewsUrl}/admin/${enc(reviewId)}/restore`)).data
