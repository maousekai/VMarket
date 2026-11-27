import apiClient from './apiClient'

// Delivery Service (PBL6-20). Seller ban giao phan hang cua shop minh; nguoi mua / seller xem
// trang thai chuyen giao. 404 DELIVERY_NOT_FOUND = don chua co chuyen giao.
// Nhan trang thai dung chung nam o utils/deliveryStatus.js (review PR 68, muc 1.3).
export { DELIVERY_STATUS_LABELS } from '../../utils/deliveryStatus'

/** Trang thai ma delivery-service nhan GPS va day vi tri qua WebSocket (DeliveryStatus.tracking). */
export const TRACKING_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'])
export const isTrackingStatus = (status) => TRACKING_STATUSES.includes(status)
export const isTerminalStatus = (status) => status === 'DELIVERED' || status === 'RETURNED'

/** Server tu choi GPS gui cach nhau duoi 10 giay (PING_TOO_FREQUENT). */
export const GPS_MIN_INTERVAL_MS = 10_000
/**
 * Web gui moi 12 giay: gui dung 10 giay thi do lech dong ho / mang lam 200 va 429 xen ke, vi tri
 * thuc te 20 giay moi cap nhat mot lan (review PR 68).
 */
export const GPS_SEND_INTERVAL_MS = 12_000

// Do lech dong ho may khach so voi server (header Date), de "x giay truoc" tinh theo gio server.
let serverOffsetMs = 0
function recordServerClock(response) {
  const serverTime = Date.parse(response?.headers?.date || '')
  if (Number.isFinite(serverTime)) serverOffsetMs = serverTime - Date.now()
  return response
}
/** Gio hien tai uoc theo dong ho server. */
export const serverNow = () => Date.now() + serverOffsetMs

const enc = encodeURIComponent
const orderUrl = (orderId) => `/api/delivery/orders/${enc(orderId)}`

// ---- Seller / buyer ----

export const handoffOrder = async (orderId) =>
  (await apiClient.post(`/api/delivery/seller/orders/${enc(orderId)}/handoff`)).data

/** {assignment:{status,version,...}, order:{...}, pickups:[...], history:[{status, occurred_at}]} */
export const getDelivery = async (orderId) =>
  recordServerClock(await apiClient.get(orderUrl(orderId))).data

/** Lich su hanh trinh: [{sequence, latitude, longitude, recorded_at}] theo thu tu ghi. */
export const getRoute = async (orderId, { page = 0, size = 500 } = {}) =>
  recordServerClock(await apiClient.get(`${orderUrl(orderId)}/route`, { params: { page, size } }))
    .data

/** Ve dung mot lan, song 30 giay: {ticket, webSocketUrl}. */
export const issueTrackingTicket = async (orderId) =>
  recordServerClock(await apiClient.post(`${orderUrl(orderId)}/tracking-ticket`)).data

// ---- Admin ----

/**
 * [{id, name, phone, province, district, active, account_status, total_orders, active_orders,
 *   delivered_orders, success_rate_percent}] - province/district la khoa da chuan hoa.
 */
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

/** [{id, order_id, shipper_id, shipper_name, shipper_phone, status, attempt, version, ...}] */
export const listAssignments = async ({ status = '', page = 0, size = 20 } = {}) =>
  (
    await apiClient.get('/api/delivery/admin/assignments', {
      params: { page, size, ...(status ? { status } : {}) },
    })
  ).data

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
