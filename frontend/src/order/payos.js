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

// Hang doi thanh toan cua mot lan dat nhieu don (moi gian hang mot don) + payment id tung don
// de trang /payment/return goi dong bo trang thai.
const QUEUE_KEY = 'vmarket.payosCheckout'

export function readPayosCheckout() {
  try {
    const value = JSON.parse(sessionStorage.getItem(QUEUE_KEY) || 'null')
    return value && Array.isArray(value.queue) ? value : { queue: [], payments: {} }
  } catch {
    return { queue: [], payments: {} }
  }
}

function writePayosCheckout(value) {
  try {
    sessionStorage.setItem(QUEUE_KEY, JSON.stringify(value))
  } catch {
    // Private mode: the return page falls back to the order list.
  }
}

/** Tao giao dich PayOS va chuyen trinh duyet sang trang thanh toan. */
export async function startPayosPayment(orderId, navigate) {
  const order = await waitForReservation(orderId)
  if (order.status === 'CANCELLED') {
    throw Object.assign(new Error('Sản phẩm vừa hết hàng nên đơn đã bị huỷ.'), { order })
  }
  const payment = await createPayment(orderId)
  if (!payment.checkoutUrl) throw new Error('PayOS chưa trả về đường dẫn thanh toán.')
  const checkout = readPayosCheckout()
  writePayosCheckout({
    queue: checkout.queue.includes(orderId) ? checkout.queue : [...checkout.queue, orderId],
    payments: { ...checkout.payments, [orderId]: payment.id },
  })
  try {
    sessionStorage.setItem(PENDING_PAYMENT_KEY, orderId)
  } catch {
    // ignore
  }
  if (navigate) navigate(payment.checkoutUrl)
  else window.location.assign(payment.checkoutUrl)
  return payment
}

/** Bat dau thanh toan lan luot cac don vua dat: mo don dau tien, cac don con lai cho sau. */
export async function startPayosQueue(orderIds, navigate) {
  writePayosCheckout({ queue: [...orderIds], payments: {} })
  return startPayosPayment(orderIds[0], navigate)
}

/** Xoa dau vet thanh toan khi khong con don nao cho thanh toan. */
export function clearPayosCheckout() {
  try {
    sessionStorage.removeItem(QUEUE_KEY)
    sessionStorage.removeItem(PENDING_PAYMENT_KEY)
  } catch {
    // ignore
  }
}
