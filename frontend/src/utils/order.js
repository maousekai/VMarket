// Trang thai don hang / tra hang cua Order Service (entity/OrderStatus, ReturnStatus).

export const ORDER_STATUS_LABELS = Object.freeze({
  WAITING_PAYMENT: 'Chờ thanh toán',
  PENDING: 'Chờ xác nhận',
  PROCESSING: 'Đang chuẩn bị',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã huỷ',
})

export const RETURN_STATUS_LABELS = Object.freeze({
  PENDING: 'Chờ gian hàng xử lý',
  APPROVED: 'Gian hàng đồng ý, chờ nhận hàng trả',
  REJECTED: 'Gian hàng từ chối',
  DISPUTED: 'Đang khiếu nại lên sàn',
  RESOLVED: 'Đã chấp nhận hoàn tiền',
  ADMIN_REJECTED: 'Sàn từ chối khiếu nại',
  CANCELLED: 'Đã huỷ',
})

export const PAYMENT_METHOD_LABELS = Object.freeze({
  COD: 'Thanh toán khi nhận hàng (COD)',
  PAYOS: 'Chuyển khoản / QR qua PayOS',
})

/** Cac buoc timeline theo state machine; CANCELLED hien rieng. */
export const ORDER_STEPS = Object.freeze([
  { key: 'PLACED', label: 'Đặt hàng' },
  { key: 'PROCESSING', label: 'Đã xác nhận' },
  { key: 'SHIPPED', label: 'Đang giao' },
  { key: 'DELIVERED', label: 'Đã giao' },
  { key: 'COMPLETED', label: 'Hoàn thành' },
])

const STEP_INDEX = {
  WAITING_PAYMENT: 0,
  PENDING: 0,
  PROCESSING: 1,
  SHIPPED: 2,
  DELIVERED: 3,
  COMPLETED: 4,
}

/** Chi so buoc hien tai (0..4), -1 khi don da huy. */
export const orderStepIndex = (status) => (status === 'CANCELLED' ? -1 : (STEP_INDEX[status] ?? 0))

export const RETURN_WINDOW_DAYS = 7
const DAY_MS = 24 * 60 * 60 * 1000

export const canCancelOrder = (order) => ['PENDING', 'WAITING_PAYMENT'].includes(order?.status)

export const canCompleteOrder = (order) => order?.status === 'DELIVERED'

/** Han tra hang (deliveredAt + 7 ngay) hoac null khi chua biet thoi diem giao. */
export function returnDeadline(order) {
  if (!order?.deliveredAt) return null
  return new Date(new Date(order.deliveredAt).getTime() + RETURN_WINDOW_DAYS * DAY_MS)
}

/** Con dong hang nao chua yeu cau tra va con trong han 7 ngay ke tu khi giao. */
export function canRequestReturn(order, returns = [], now = new Date()) {
  if (order?.status !== 'DELIVERED') return false
  const deadline = returnDeadline(order)
  if (deadline && now > deadline) return false
  return returnableItems(order, returns).length > 0
}

/** Dong hang chua nam trong yeu cau tra hang nao (moi dong chi tra duoc mot lan). */
export function returnableItems(order, returns = []) {
  const requested = new Set(
    returns
      .filter((item) => item.status !== 'CANCELLED')
      .flatMap((item) => (item.items || []).map((line) => line.orderItemId)),
  )
  return (order?.items || []).filter((item) => item.id && !requested.has(item.id))
}

/** Nhom dong hang theo gian hang (don nhieu shop: moi shop xac nhan / giao phan cua minh). */
export function groupItemsByShop(items = []) {
  const groups = new Map()
  for (const item of items) {
    if (!groups.has(item.shopId)) groups.set(item.shopId, [])
    groups.get(item.shopId).push(item)
  }
  return [...groups].map(([shopId, shopItems]) => ({ shopId, items: shopItems }))
}

/** Ma don ngan de hien thi (8 ky tu cuoi cua ULID). */
export const shortOrderId = (id) => (id ? `#${String(id).slice(-8).toUpperCase()}` : '')

export const orderAddress = (order) =>
  [order?.streetAddress, order?.ward, order?.district, order?.province].filter(Boolean).join(', ')
