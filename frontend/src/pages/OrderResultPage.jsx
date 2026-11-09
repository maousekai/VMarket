import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { getOrder } from '../services/api/orderApi'
import { PENDING_PAYMENT_KEY } from '../order/payos'
import { formatMoney } from '../utils/formatMoney'
import {
  ORDER_STATUS_LABELS,
  orderAddress,
  PAYMENT_METHOD_LABELS,
  shortOrderId,
} from '../utils/order'
import '../components/account.css'
import '../components/cart.css'
import '../components/checkout.css'

const PAID_POLL_MS = 1500
const PAID_POLL_TRIES = 10

function readPendingOrder() {
  try {
    return sessionStorage.getItem(PENDING_PAYMENT_KEY)
  } catch {
    return null
  }
}

function clearPendingOrder() {
  try {
    sessionStorage.removeItem(PENDING_PAYMENT_KEY)
  } catch {
    // ignore
  }
}

const COPY = {
  success: {
    title: 'Đặt hàng thành công',
    text: 'Gian hàng sẽ xác nhận đơn trong thời gian sớm nhất. Bạn thanh toán khi nhận hàng.',
  },
  paid: {
    title: 'Thanh toán thành công',
    text: 'VMarket đã nhận thanh toán, đơn đang chờ gian hàng xác nhận.',
  },
  waiting: {
    title: 'Đang xác nhận thanh toán',
    text: 'PayOS đã chuyển bạn về VMarket. Hệ thống đang chờ xác nhận giao dịch, bạn có thể xem trạng thái trong Đơn mua.',
  },
  cancelled: {
    title: 'Bạn đã huỷ thanh toán',
    text: 'Đơn vẫn được giữ trong thời gian thanh toán. Bạn có thể thanh toán lại hoặc huỷ đơn trong Đơn mua.',
  },
  failed: {
    title: 'Đơn hàng đã bị huỷ',
    text: 'Thanh toán không thành công hoặc đã hết hạn nên đơn đã được huỷ và hàng được trả lại kho.',
  },
}

/**
 * - mode="success": sau khi dat don COD (/checkout/success/:orderId).
 * - mode="return" | "cancel": PayOS chuyen ve /payment/return hoac /payment/cancel.
 */
export default function OrderResultPage({ mode = 'success' }) {
  const params = useParams()
  const orderId = params.orderId || readPendingOrder()
  const [state, setState] = useState({ status: orderId ? 'loading' : 'missing', order: null })

  useEffect(() => {
    if (!orderId) return undefined
    let current = true
    let timer
    const poll = async (attempt) => {
      try {
        const order = await getOrder(orderId)
        if (!current) return
        const stillWaiting = mode === 'return' && order.status === 'WAITING_PAYMENT'
        if (stillWaiting && attempt < PAID_POLL_TRIES) {
          setState({ status: 'polling', order })
          timer = setTimeout(() => poll(attempt + 1), PAID_POLL_MS)
          return
        }
        if (mode !== 'cancel' && order.status !== 'WAITING_PAYMENT') clearPendingOrder()
        setState({ status: 'ready', order })
      } catch (failure) {
        if (current) setState({ status: 'error', order: null, error: getApiError(failure).message })
      }
    }
    poll(0)
    return () => {
      current = false
      clearTimeout(timer)
    }
  }, [orderId, mode])

  const order = state.order
  let copy = COPY.success
  if (order?.status === 'CANCELLED') copy = COPY.failed
  else if (mode === 'cancel') copy = COPY.cancelled
  else if (mode === 'return') copy = order?.status === 'WAITING_PAYMENT' ? COPY.waiting : COPY.paid

  if (state.status === 'missing') {
    return (
      <main className="cart-page">
        <div className="account-card cart-empty">
          <h1>Không tìm thấy đơn vừa thanh toán</h1>
          <Link className="button button--primary" to="/profile/orders">
            Xem đơn mua
          </Link>
        </div>
      </main>
    )
  }

  return (
    <main className="cart-page checkout-page">
      <ol className="checkout-steps" aria-label="Các bước đặt hàng">
        <li className="is-done">Giỏ hàng</li>
        <li className="is-done">Thanh toán</li>
        <li className="is-current" aria-current="step">
          Hoàn tất
        </li>
      </ol>
      <section
        className={`account-card order-result${order?.status === 'CANCELLED' || mode === 'cancel' ? ' is-warning' : ''}`}
        aria-live="polite"
      >
        {state.status === 'loading' && <p role="status">Đang tải đơn hàng…</p>}
        {state.status === 'error' && <p role="alert">{state.error}</p>}
        {order && (
          <>
            <h1>{copy.title}</h1>
            <p>{copy.text}</p>
            {state.status === 'polling' && (
              <p className="account-notice" role="status">
                Đang chờ PayOS xác nhận…
              </p>
            )}
            <dl className="order-result__facts">
              <div>
                <dt>Mã đơn</dt>
                <dd>{shortOrderId(order.id)}</dd>
              </div>
              <div>
                <dt>Trạng thái</dt>
                <dd>{ORDER_STATUS_LABELS[order.status] || order.status}</dd>
              </div>
              <div>
                <dt>Thanh toán</dt>
                <dd>{PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod}</dd>
              </div>
              <div>
                <dt>Tổng tiền</dt>
                <dd>{formatMoney(order.totalAmount)}</dd>
              </div>
              <div>
                <dt>Giao đến</dt>
                <dd>
                  {order.recipientName} · {order.phone}
                  <br />
                  {orderAddress(order)}
                </dd>
              </div>
            </dl>
            <div className="account-actions">
              <Link
                className="button button--primary"
                to={`/profile/orders/${encodeURIComponent(order.id)}`}
              >
                Xem chi tiết đơn
              </Link>
              <Link className="button button--secondary" to="/">
                Tiếp tục mua sắm
              </Link>
            </div>
          </>
        )}
      </section>
    </main>
  )
}
