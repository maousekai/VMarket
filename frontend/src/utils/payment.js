/** Chu ky hoi lai trang thai khi dang cho nguoi mua thanh toan tren VNPay. */
export const PAYMENT_POLL_MS = 4000

// Trang thai giao dich cua Payment Service (PaymentTransaction.status).
export const PAYMENT_STATUS_LABELS = Object.freeze({
  CREATED: 'Đang tạo liên kết thanh toán',
  CREATING: 'Đang tạo liên kết thanh toán',
  PENDING: 'Chờ thanh toán',
  AWAITING_WEBHOOK: 'Đang xác nhận với ngân hàng',
  RECONCILING: 'Đang đối soát',
  SUCCEEDED: 'Đã thanh toán',
  FAILED: 'Thanh toán thất bại',
  EXPIRED: 'Hết hạn thanh toán',
  CANCELLED: 'Đã huỷ',
  COD_PENDING: 'Thu tiền khi giao',
  COMPLETED: 'Đã thu tiền',
})

const TONES = {
  SUCCEEDED: 'success',
  COMPLETED: 'success',
  FAILED: 'danger',
  EXPIRED: 'danger',
  CANCELLED: 'muted',
}

export const paymentTone = (status) => TONES[status] || 'warning'

export const REFUND_STATUS_LABELS = Object.freeze({
  PENDING: 'Chờ chuyển khoản hoàn tiền',
  COMPLETED: 'Đã hoàn tiền',
})

/** Giao dich VNPay con dang cho nguoi mua thanh toan. */
export const isAwaitingPayment = (payment) =>
  payment?.method === 'VNPAY' &&
  !payment.paidAt &&
  ['CREATED', 'CREATING', 'PENDING'].includes(payment.status)

/** Thoi diem het han hien cho nguoi mua: han cua link VNPay, khong co thi han giu don. */
export const paymentDeadline = (payment) => payment?.linkExpiresAt || payment?.expiresAt || null

/** "mm:ss" con lai (00:00 khi da het han). */
export function formatCountdown(ms) {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = String(Math.floor(seconds / 60)).padStart(2, '0')
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

/** Ma ngan hien thi cho giao dich (id so cua giao dich). */
export const paymentCode = (payment) => (payment?.id != null ? `GD${payment.id}` : '')
