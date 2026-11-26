import client from './client'

// Delivery Service (PBL6-20) - phan he shipper. Cung quy tac voi frontend/src/services/api/deliveryApi.js.
export const DELIVERY_STATUS_LABELS = Object.freeze({
  WAITING_HANDOFF: 'Chờ gian hàng bàn giao',
  WAITING_ASSIGNMENT: 'Chờ phân công',
  ASSIGNED: 'Chờ lấy hàng',
  PICKED_UP: 'Đã lấy hàng',
  IN_TRANSIT: 'Đang giao',
  FAILED: 'Giao thất bại',
  RETURNING: 'Đang hoàn hàng',
  RETURNED: 'Đã hoàn hàng',
  DELIVERED: 'Đã giao',
})

/** Trang thai server nhan GPS (DeliveryStatus.tracking). */
export const TRACKING_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'])
export const isTrackingStatus = (status) => TRACKING_STATUSES.includes(status)
export const isTerminalStatus = (status) => status === 'DELIVERED' || status === 'RETURNED'

/** Server tu choi diem GPS cach nhau duoi 10 giay; app gui moi 15 giay (yeu cau 10-30s). */
export const GPS_INTERVAL_MS = 15_000

const enc = encodeURIComponent
const base = '/api/delivery/shipper/assignments'

export const listMyAssignments = async ({ page = 0, size = 50 } = {}) =>
  (await client.get(base, { params: { page, size } })).data

export const updateAssignmentStatus = async (id, body) =>
  (await client.put(`${base}/${enc(id)}/status`, body)).data

export const retryAssignment = async (id, expectedVersion) =>
  (await client.post(`${base}/${enc(id)}/retry`, { expectedVersion })).data

export const sendLocation = async (id, { latitude, longitude }) =>
  (await client.post(`${base}/${enc(id)}/location`, { latitude, longitude })).data

export function parseOrderSnapshot(snapshot) {
  if (!snapshot) return {}
  if (typeof snapshot === 'object') return snapshot
  try {
    return JSON.parse(snapshot) || {}
  } catch {
    return {}
  }
}

export const orderAddress = (order) =>
  [order?.streetAddress, order?.ward, order?.district, order?.province].filter(Boolean).join(', ')

/** Hanh dong hop le theo DeliveryService.status/retry. */
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

const money = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' })
export const formatMoney = (value) => money.format(Number(value || 0))
export const shortOrderId = (id) => (id ? `#${String(id).slice(-8).toUpperCase()}` : '')
