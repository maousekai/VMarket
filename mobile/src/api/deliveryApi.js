import client from './client'
import { MAX_ATTEMPTS } from '../deliveryStatus'

// Delivery Service (PBL6-20) - phan he shipper. Cung quy tac voi frontend/src/services/api/deliveryApi.js.
export { DELIVERY_STATUS_LABELS } from '../deliveryStatus'

/** Trang thai server nhan GPS (DeliveryStatus.tracking). */
export const TRACKING_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'])
export const isTrackingStatus = (status) => TRACKING_STATUSES.includes(status)
export const isTerminalStatus = (status) =>
  ['DELIVERED', 'RETURN_DROPPED_OFF', 'RETURNED'].includes(status)

/** Server tu choi diem GPS cach nhau duoi 10 giay; app gui moi 15 giay (yeu cau 10-30s). */
export const GPS_INTERVAL_MS = 15_000

const enc = encodeURIComponent
const base = '/api/delivery/shipper/assignments'
const orderUrl = (id) => `/api/delivery/orders/${enc(id)}`
let serverOffset = 0
export const serverNow = () => Date.now() + serverOffset

function withServerClock(response) {
  const timestamp = Date.parse(response.headers?.date)
  if (Number.isFinite(timestamp)) serverOffset = timestamp - Date.now()
  return response.data
}

export const listMyAssignments = async ({ scope = 'active', page = 0, size = 20, signal } = {}) =>
  withServerClock(await client.get(base, { params: { scope, page, size }, signal }))

export const getDelivery = async (id, signal) => (await client.get(orderUrl(id), { signal })).data

export const issueTrackingTicket = async (id, signal) =>
  (await client.post(`${orderUrl(id)}/tracking-ticket`, null, { signal })).data

export const confirmPickup = async (id, shopId, expectedVersion) =>
  (await client.post(`${base}/${enc(id)}/pickups/${enc(shopId)}`, { expectedVersion })).data

export const confirmReturn = async (id, shopId, expectedVersion) =>
  (await client.post(`${base}/${enc(id)}/returns/${enc(shopId)}`, { expectedVersion })).data

export const updateAssignmentStatus = async (id, body) =>
  (await client.put(`${base}/${enc(id)}/status`, body)).data

export const retryAssignment = async (id, expectedVersion) =>
  (await client.post(`${base}/${enc(id)}/retry`, { expectedVersion })).data

export const sendLocations = async ({ latitude, longitude }) =>
  (await client.post('/api/delivery/shipper/locations', { latitude, longitude, source: 'MOBILE' }))
    .data

/** Hanh dong hop le theo DeliveryService.status/retry. */
export function shipperActions(assignment) {
  switch (assignment?.status) {
    case 'PICKED_UP':
      return [{ kind: 'status', status: 'IN_TRANSIT', label: 'Bắt đầu giao' }]
    case 'IN_TRANSIT':
      // Nhan theo Figma (3.14): COD thi xac nhan thu tien khi giao.
      return [
        {
          kind: 'deliver',
          status: 'DELIVERED',
          label:
            assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0
              ? 'Giao hàng và xác nhận COD'
              : 'Đã giao hàng',
        },
        { kind: 'fail', status: 'FAILED', label: 'Không giao được', danger: true },
      ]
    case 'FAILED':
      return assignment.attempt < (assignment.maxAttempts || MAX_ATTEMPTS)
        ? [{ kind: 'retry', label: 'Giao lại' }]
        : []
    default:
      return []
  }
}

const money = new Intl.NumberFormat('vi-VN')
/** "135.000đ" theo cach viet trong Figma. */
export const formatMoney = (value) => `${money.format(Math.round(Number(value || 0)))}đ`
export const shortOrderId = (id) => (id ? `#${String(id).slice(-8).toUpperCase()}` : '')

// Ca lam viec (review PR 68 lan 3, phan 2): {onShift, shiftChangedAt, unpickedTrips, activeTrips}.
export const getShift = async () => (await client.get('/api/delivery/shipper/shift')).data

export const startShift = async () => (await client.post('/api/delivery/shipper/shift/start')).data

export const endShift = async ({ releaseUnpicked = false } = {}) =>
  (await client.post('/api/delivery/shipper/shift/end', { releaseUnpicked })).data
