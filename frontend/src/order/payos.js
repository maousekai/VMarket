import { getOrder } from '../services/api/orderApi'
import { createPayment } from '../services/api/paymentApi'

// Luong PayOS: don PAYOS tao o trang thai WAITING_PAYMENT; Payment Service chi tao link khi
// Product Service da giu hang (stockReserved). Nguoi mua quet QR o trang /payment/:orderId; neu mo
// trang PayOS thi PayOS chuyen ve /payment/return, order id nho trong sessionStorage.
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

// Hang doi thanh toan cua mot lan dat nhieu don (moi gian hang mot don) + payment id tung don.
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
    // Private mode: the payment pages fall back to the order list.
  }
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

/**
 * Tao (hoac lay lai) giao dich PayOS cua don sau khi hang da duoc giu, va ghi nho payment id de
 * trang ket qua dong bo trang thai.
 */
export async function preparePayosPayment(orderId, options) {
  const order = await waitForReservation(orderId, options)
  if (order.status === 'CANCELLED') {
    throw Object.assign(new Error('Sản phẩm vừa hết hàng hoặc đơn đã huỷ.'), { order })
  }
  if (order.status !== 'WAITING_PAYMENT') {
    throw Object.assign(new Error('Đơn này không còn chờ thanh toán.'), { order })
  }
  const payment = await createPayment(orderId)
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
  return payment
}

/** Don tiep theo trong hang doi (khac don hien tai) van con cho thanh toan. */
export async function findNextUnpaid(currentOrderId) {
  for (const id of readPayosCheckout().queue.filter((item) => item !== currentOrderId)) {
    try {
      const order = await getOrder(id)
      if (order.status === 'WAITING_PAYMENT') return order
    } catch {
      // Skip an order we cannot read; it stays in Đơn mua.
    }
  }
  return null
}

/** Bat dau thanh toan lan luot cac don vua dat: mo man QR cua don dau tien. */
export function startPayosQueue(orderIds, navigate) {
  writePayosCheckout({ queue: [...orderIds], payments: {} })
  navigate(`/payment/${encodeURIComponent(orderIds[0])}`, { replace: true })
}
