import apiClient from './apiClient'

// Delivery Service (PBL6-20). Seller ban giao phan hang cua shop minh; nguoi mua / seller xem
// trang thai chuyen giao. 404 DELIVERY_NOT_FOUND = don chua co chuyen giao.
export const DELIVERY_STATUS_LABELS = Object.freeze({
  WAITING_HANDOFF: 'Chờ các gian hàng bàn giao',
  WAITING_ASSIGNMENT: 'Chờ phân công shipper',
  ASSIGNED: 'Đã có shipper nhận đơn',
  PICKED_UP: 'Shipper đã lấy hàng',
  IN_TRANSIT: 'Đang giao hàng',
  FAILED: 'Giao không thành công, sẽ giao lại',
  RETURNING: 'Đang hoàn hàng về gian hàng',
  RETURNED: 'Đã hoàn hàng',
  DELIVERED: 'Đã giao thành công',
})

/** Trang thai ma delivery-service nhan GPS va day vi tri qua WebSocket (DeliveryStatus.tracking). */
export const TRACKING_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'])
export const isTrackingStatus = (status) => TRACKING_STATUSES.includes(status)
export const isTerminalStatus = (status) => status === 'DELIVERED' || status === 'RETURNED'

/** Server tu choi GPS gui cach nhau duoi 10 giay (PING_TOO_FREQUENT). */
export const GPS_MIN_INTERVAL_MS = 10_000

const enc = encodeURIComponent
const orderUrl = (orderId) => `/api/delivery/orders/${enc(orderId)}`

// ---- Seller / buyer ----

export const handoffOrder = async (orderId) =>
  (await apiClient.post(`/api/delivery/seller/orders/${enc(orderId)}/handoff`)).data

/** {assignment:{status,version,...}, order:{...}, pickups:[...], history:[{status, occurred_at}]} */
export const getDelivery = async (orderId) => (await apiClient.get(orderUrl(orderId))).data

/** Lich su hanh trinh: [{sequence, latitude, longitude, recorded_at}] theo thu tu ghi. */
export const getRoute = async (orderId, { page = 0, size = 500 } = {}) =>
  (await apiClient.get(`${orderUrl(orderId)}/route`, { params: { page, size } })).data

/** Ve dung mot lan, song 30 giay: {ticket, webSocketUrl}. */
export const issueTrackingTicket = async (orderId) =>
  (await apiClient.post(`${orderUrl(orderId)}/tracking-ticket`)).data

// ---- Admin ----

/** [{id,name,phone,province,district,active,total_orders,delivered_orders,success_rate_percent}] */
export const listShippers = async ({ province = '', district = '', page = 0, size = 20 } = {}) =>
  (
    await apiClient.get('/api/delivery/admin/shippers', {
      params: { province, district, page, size },
    })
  ).data

const idempotencyKey = () =>
  (globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`).replace(/[^\w-]/g, '')

/**
 * Tao tai khoan + ho so shipper. `key` giu nguyen khi bam lai cung mot form de khong tao trung
 * khi request dau da toi server nhung mat phan hoi.
 */
export const createShipper = async (body, key = idempotencyKey()) =>
  (
    await apiClient.post('/api/delivery/admin/shippers', body, {
      headers: { 'Idempotency-Key': key },
    })
  ).data

export const newIdempotencyKey = idempotencyKey

export const setShipperLocked = async (shipperId, locked) =>
  (await apiClient.put(`/api/delivery/admin/shippers/${enc(shipperId)}/lock`, { locked })).data

/** [{id, order_id, shipper_id, status, attempt, province, district, created_at}] */
export const listAssignments = async ({ page = 0, size = 20 } = {}) =>
  (await apiClient.get('/api/delivery/admin/assignments', { params: { page, size } })).data

export const assignShipper = async (assignmentId, body) =>
  (await apiClient.post(`/api/delivery/admin/assignments/${enc(assignmentId)}/assign`, body)).data

export const returnAssignment = async (assignmentId, body) =>
  (await apiClient.post(`/api/delivery/admin/assignments/${enc(assignmentId)}/return`, body)).data

export const adminRetryAssignment = async (assignmentId, expectedVersion) =>
  (
    await apiClient.post(`/api/delivery/admin/assignments/${enc(assignmentId)}/retry`, {
      expectedVersion,
    })
  ).data

// ---- Shipper ----

/** Assignment day du (camelCase), `orderSnapshot` la chuoi JSON cua don. */
export const listMyAssignments = async ({ page = 0, size = 50 } = {}) =>
  (await apiClient.get('/api/delivery/shipper/assignments', { params: { page, size } })).data

export const updateAssignmentStatus = async (assignmentId, body) =>
  (await apiClient.put(`/api/delivery/shipper/assignments/${enc(assignmentId)}/status`, body)).data

export const sendLocation = async (assignmentId, { latitude, longitude }) =>
  (
    await apiClient.post(`/api/delivery/shipper/assignments/${enc(assignmentId)}/location`, {
      latitude,
      longitude,
    })
  ).data

export const retryAssignment = async (assignmentId, expectedVersion) =>
  (
    await apiClient.post(`/api/delivery/shipper/assignments/${enc(assignmentId)}/retry`, {
      expectedVersion,
    })
  ).data

/** Doc orderSnapshot (chuoi JSON) an toan; loi thi tra {}. */
export function parseOrderSnapshot(snapshot) {
  if (!snapshot) return {}
  if (typeof snapshot === 'object') return snapshot
  try {
    return JSON.parse(snapshot) || {}
  } catch {
    return {}
  }
}

/**
 * Hanh dong shipper duoc phep o moi trang thai, khop voi DeliveryService.status/retry:
 * ASSIGNED->PICKED_UP->IN_TRANSIT->(DELIVERED|FAILED), FAILED->retry, RETURNING->RETURNED.
 */
export function shipperActions(assignment) {
  switch (assignment?.status) {
    case 'ASSIGNED':
      return [{ kind: 'status', status: 'PICKED_UP', label: 'Đã lấy hàng' }]
    case 'PICKED_UP':
      return [{ kind: 'status', status: 'IN_TRANSIT', label: 'Bắt đầu giao' }]
    case 'IN_TRANSIT':
      return [
        { kind: 'deliver', status: 'DELIVERED', label: 'Giao thành công' },
        { kind: 'fail', status: 'FAILED', label: 'Giao thất bại', danger: true },
      ]
    case 'FAILED':
      return assignment.attempt < 3 ? [{ kind: 'retry', label: 'Giao lại' }] : []
    case 'RETURNING':
      return [{ kind: 'status', status: 'RETURNED', label: 'Đã hoàn hàng về shop' }]
    default:
      return []
  }
}
