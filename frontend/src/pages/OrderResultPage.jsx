import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { getOrder } from '../services/api/orderApi'
import { syncPayment } from '../services/api/paymentApi'
import CheckoutOrderList from '../components/CheckoutOrderList'
import {
  clearPayosCheckout,
  findNextUnpaid,
  loadCheckoutOrders,
  PENDING_PAYMENT_KEY,
  readPayosCheckout,
} from '../order/payos'
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
    text: 'PayOS đã chuyển bạn về VMarket nhưng chưa xác nhận giao dịch. Bạn có thể xem lại trạng thái trong Đơn mua.',
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

function OrderFacts({ order }) {
  return (
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
  )
}

/** Sau khi dat don COD: /checkout/success?orders=id1,id2 (moi gian hang mot don). */
function CheckoutSuccess({ ids }) {
  const [state, setState] = useState({ status: 'loading', orders: [], error: '' })
  const key = ids.join(',')

  useEffect(() => {
    let current = true
    Promise.all(key.split(',').map((id) => getOrder(id)))
      .then((orders) => current && setState({ status: 'ready', orders, error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', orders: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [key])

  return (
    <section className="account-card order-result" aria-live="polite">
      {state.status === 'loading' && <p role="status">Đang tải đơn hàng…</p>}
      {state.status === 'error' && <p role="alert">{state.error}</p>}
      {state.status === 'ready' && (
        <>
          <h1>{COPY.success.title}</h1>
          <p>
            {state.orders.length > 1
              ? `Đã tạo ${state.orders.length} đơn theo từng gian hàng. ${COPY.success.text}`
              : COPY.success.text}
          </p>
          <CheckoutOrderList orders={state.orders} />
          <div className="account-actions">
            <Link className="button button--primary" to="/profile/orders">
              Xem đơn mua
            </Link>
            <Link className="button button--secondary" to="/">
              Tiếp tục mua sắm
            </Link>
          </div>
        </>
      )}
    </section>
  )
}

/** PayOS chuyen ve /payment/return hoac /payment/cancel. */
function PaymentResult({ mode }) {
  const [orderId] = useState(readPendingOrder)
  const [state, setState] = useState({ status: orderId ? 'loading' : 'missing', order: null })
  const [next, setNext] = useState(null)
  const [batch, setBatch] = useState([])

  useEffect(() => {
    if (!orderId) return undefined
    let current = true
    let timer
    const checkout = readPayosCheckout()
    const paymentId = checkout.payments[orderId]
    const poll = async (attempt) => {
      try {
        // Khong co webhook o moi truong local: hoi PayOS truc tiep truoc khi doc don.
        if (mode === 'return' && paymentId) await syncPayment(paymentId).catch(() => null)
        const order = await getOrder(orderId)
        if (!current) return
        if (mode === 'return' && order.status === 'WAITING_PAYMENT' && attempt < PAID_POLL_TRIES) {
          setState({ status: 'polling', order })
          timer = setTimeout(() => poll(attempt + 1), PAID_POLL_MS)
          return
        }
        setState({ status: 'ready', order })
        const following = await findNextUnpaid(orderId)
        if (!current) return
        setNext(following)
        // Het don cho thanh toan thi liet ke du cac don roi xoa hang doi; F5 se dua ve Don mua.
        if (!following && order.status !== 'WAITING_PAYMENT') {
          const orders = await loadCheckoutOrders()
          if (!current) return
          setBatch(orders)
          clearPayosCheckout()
        }
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

  if (state.status === 'missing') {
    return (
      <div className="account-card cart-empty">
        <h1>Không tìm thấy đơn vừa thanh toán</h1>
        <Link className="button button--primary" to="/profile/orders">
          Xem đơn mua
        </Link>
      </div>
    )
  }

  const order = state.order
  let copy = COPY.paid
  if (order?.status === 'CANCELLED') copy = COPY.failed
  else if (mode === 'cancel') copy = COPY.cancelled
  else if (order?.status === 'WAITING_PAYMENT') copy = COPY.waiting

  return (
    <section
      className={`account-card order-result${order?.status === 'CANCELLED' || mode === 'cancel' ? ' is-warning' : ''}`}
      aria-live="polite"
    >
      {state.status === 'loading' && <p role="status">Đang kiểm tra thanh toán…</p>}
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
          {batch.length > 1 ? (
            <CheckoutOrderList orders={batch} label="Các đơn của lần đặt hàng này" />
          ) : (
            <OrderFacts order={order} />
          )}
          {next && (
            <div className="order-result__next">
              <p>
                Bạn còn đơn {shortOrderId(next.id)} ({formatMoney(next.totalAmount)}) của gian hàng
                khác chưa thanh toán.
              </p>
              <Link
                className="button button--primary"
                to={`/payment/${encodeURIComponent(next.id)}`}
                replace
              >
                Thanh toán đơn tiếp theo
              </Link>
            </div>
          )}
          <div className="account-actions">
            <Link
              className={`button ${next ? 'button--secondary' : 'button--primary'}`}
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
  )
}

/**
 * - mode="success": sau khi dat don COD (/checkout/success?orders=... hoac /:orderId cu).
 * - mode="return" | "cancel": PayOS chuyen ve /payment/return hoac /payment/cancel.
 */
export default function OrderResultPage({ mode = 'success' }) {
  const params = useParams()
  const [search] = useSearchParams()
  const ids = (search.get('orders') || params.orderId || '').split(',').filter(Boolean)

  return (
    <main className="cart-page checkout-page">
      <ol className="checkout-steps" aria-label="Các bước đặt hàng">
        <li className="is-done">Giỏ hàng</li>
        <li className="is-done">Thanh toán</li>
        <li className="is-current" aria-current="step">
          Hoàn tất
        </li>
      </ol>
      {mode === 'success' ? (
        ids.length ? (
          <CheckoutSuccess ids={ids} />
        ) : (
          <div className="account-card cart-empty">
            <h1>Không tìm thấy đơn vừa đặt</h1>
            <Link className="button button--primary" to="/profile/orders">
              Xem đơn mua
            </Link>
          </div>
        )
      ) : (
        <PaymentResult mode={mode} />
      )}
    </main>
  )
}
