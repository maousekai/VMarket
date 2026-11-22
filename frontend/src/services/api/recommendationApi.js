import apiClient from './apiClient'

// Recommendation Service (PBL6-24) qua gateway. Doc goi y la route cong khai: khach va user
// chua co lich su nhan san pham pho bien (cold-start do service tu xu ly).
const BASE = '/api/ai/recommendations'
const publicRequest = { skipAuth: true }

const items = (data) => (Array.isArray(data?.items) ? data.items : [])

/**
 * "Gợi ý cho bạn". Co phien thi gui token de duoc ca nhan hoa; token hong (401 sau khi da thu
 * refresh) thi doc lai nhu khach thay vi lam trong trang chu.
 * `categoryId` gioi han theo danh muc (FR-REC-04: khach dang xem danh muc nao).
 */
export async function getForYou({ limit = 8, categoryId } = {}) {
  const params = { limit }
  if (categoryId) params.category_id = categoryId
  try {
    return items((await apiClient.get(`${BASE}/for-you`, { params })).data)
  } catch (error) {
    if (error?.response?.status !== 401) throw error
    return items((await apiClient.get(`${BASE}/for-you`, { ...publicRequest, params })).data)
  }
}

/** San pham tuong tu (cung danh muc, noi dung, gia gan). 404 khi service chua biet san pham. */
export async function getSimilar(productId, { limit = 10 } = {}) {
  const { data } = await apiClient.get(`${BASE}/similar/${encodeURIComponent(productId)}`, {
    ...publicRequest,
    params: { limit },
  })
  return items(data)
}

/**
 * Ghi nhan luot xem cua nguoi da dang nhap (FR-REC-03). Service lay userId tu token va dua
 * su kien UserBehaviorTracked len Event Bus; trinh duyet khong bao gio noi chuyen voi broker.
 */
export async function trackView(productId) {
  await apiClient.post(`${BASE}/events`, { productId, action: 'view' })
}
