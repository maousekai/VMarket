import { getOrder } from '../services/api/orderApi'
import { createPayment } from '../services/api/paymentApi'

// Luong PayOS: don PAYOS tao o trang thai WAITING_PAYMENT; Payment Service chi tao link khi
// Product Service da giu hang (stockReserved). Order id duoc nho trong sessionStorage de trang
// /payment/return biet don nao vua thanh toan.
export const PENDING_PAYMENT_KEY = 'vmarket.pendingPaymentOrder'
const RESERVE_POLL_MS = 1000
const RESERVE_POLL_TRIES = 20

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** Cho Product Service giu hang (StockReserved) truoc khi tao link PayOS. */
export async function waitForReservation(
  orderId,
  { tries = RESERVE_POLL_TRIES, wait = sleep } = {},
) {
  for (let attempt = 0; attempt < tries; attempt += 1) {
    const order = await getOrder(orderId)
    if (order.stockReserved || order.status === 'CANCELLED') return order
    await wait(RESERVE_POLL_MS)
  }
  return getOrder(orderId)
}

/** Tao giao dich PayOS va chuyen trinh duyet sang trang thanh toan. */
export async function startPayosPayment(orderId, navigate) {
  const order = await waitForReservation(orderId)
  if (order.status === 'CANCELLED') {
    throw Object.assign(new Error('Sản phẩm vừa hết hàng nên đơn đã bị huỷ.'), { order })
  }
  const payment = await createPayment(orderId)
  if (!payment.checkoutUrl) throw new Error('PayOS chưa trả về đường dẫn thanh toán.')
  try {
    sessionStorage.setItem(PENDING_PAYMENT_KEY, orderId)
  } catch {
    // Private mode: the return page falls back to the order list.
  }
  if (navigate) navigate(payment.checkoutUrl)
  else window.location.assign(payment.checkoutUrl)
  return payment
}
