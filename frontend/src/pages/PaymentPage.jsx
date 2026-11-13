import { useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { getPaymentByOrder } from '../services/api/paymentApi'
import CheckoutOrderList from '../components/CheckoutOrderList'
import {
  clearVnpayCheckout,
  findNextUnpaid,
  loadCheckoutOrders,
  prepareVnpayPayment,
} from '../order/vnpay'
import { formatMoney } from '../utils/formatMoney'
import { shortOrderId } from '../utils/order'
import {
  formatCountdown,
  isAwaitingPayment,
  paymentCode,
  paymentDeadline,
  PAYMENT_POLL_MS,
  PAYMENT_STATUS_LABELS,
} from '../utils/payment'
import '../components/account.css'
import '../components/cart.css'
import '../components/checkout.css'
import '../components/payment.css'

const orderLink = (orderId) => `/profile/orders/${encodeURIComponent(orderId)}`

/** Dem nguoc toi `deadline`, cap nhat moi giay. */
function useCountdown(deadline) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!deadline) return undefined
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [deadline])
  return deadline ? new Date(deadline).getTime() - now : null
}

/**
 * Man thanh toan VNPay: so tien, dem nguoc han link va nut mo trang VNPay. Khi vua dat hang xong
 * (state.autoRedirect) trinh duyet tu chuyen sang VNPay; trang thai duoc hoi lai Payment Service
 * moi vai giay.
 */
export default function PaymentPage() {
  const { orderId } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const redirected = useRef(false)
  const [state, setState] = useState({ status: 'preparing', payment: null, error: '' })
  const [next, setNext] = useState(null)
  const [batch, setBatch] = useState([])
  const payment = state.payment
  const remaining = useCountdown(isAwaitingPayment(payment) ? paymentDeadline(payment) : null)
  const expired = remaining != null && remaining <= 0

  useEffect(() => {
    let current = true
    setState({ status: 'preparing', payment: null, error: '' })
    setNext(null)
    setBatch([])
    prepareVnpayPayment(orderId)
      .then((created) => current && setState({ status: 'ready', payment: created, error: '' }))
      .catch((failure) => {
        if (!current) return
        setState({
          status: 'error',
          payment: null,
          error: failure.response ? getApiError(failure).message : failure.message,
        })
      })
    return () => {
      current = false
    }
  }, [orderId])

  const paymentId = payment?.id
  const waiting = state.status === 'ready' && isAwaitingPayment(payment)
  useEffect(() => {
    if (!paymentId || !waiting) return undefined
    let current = true
    const timer = setInterval(async () => {
      if (document.visibilityState === 'hidden') return
      try {
        const latest = await getPaymentByOrder(orderId)
        if (current) setState((value) => ({ ...value, payment: latest }))
      } catch {
        // Keep the page as is; the next tick retries.
      }
    }, PAYMENT_POLL_MS)
    return () => {
      current = false
      clearInterval(timer)
    }
  }, [paymentId, waiting, orderId])

  // Vua dat hang xong: chuyen thang sang cong VNPay mot lan (quay lai trang nay thi khong tu chuyen).
  const checkoutUrl = payment?.checkoutUrl
  const autoRedirect = Boolean(location.state?.autoRedirect)
  useEffect(() => {
    if (!autoRedirect || !waiting || expired || !checkoutUrl || redirected.current) return
    redirected.current = true
    window.location.assign(checkoutUrl)
  }, [autoRedirect, waiting, expired, checkoutUrl])

  const paid = Boolean(payment?.paidAt)
  useEffect(() => {
    if (!paid) return undefined
    let current = true
    findNextUnpaid(orderId).then(async (following) => {
      if (!current) return
      setNext(following)
      if (following) return
      // Da thanh toan het: liet ke moi don cua lan dat roi moi xoa hang doi.
      const orders = await loadCheckoutOrders()
      if (!current) return
      setBatch(orders)
      clearVnpayCheckout()
    })
    return () => {
      current = false
    }
  }, [paid, orderId])

  let body
  if (state.status === 'preparing') {
    body = (
      <p className="account-notice" role="status">
        Đang tạo liên kết thanh toán…
      </p>
    )
  } else if (state.status === 'error') {
    body = (
      <section className="account-card payment-card is-warning">
        <h1>Chưa mở được thanh toán</h1>
        <p role="alert">{state.error}</p>
        <p>Đơn vẫn được giữ trong thời hạn thanh toán. Bạn có thể thử lại hoặc huỷ đơn.</p>
        <div className="account-actions">
          <button type="button" className="button button--primary" onClick={() => navigate(0)}>
            Thử lại
          </button>
          <Link className="button button--secondary" to={orderLink(orderId)}>
            Xem đơn hàng
          </Link>
        </div>
      </section>
    )
  } else if (paid) {
    body = (
      <section className="account-card payment-card is-success" aria-live="polite">
        <h1>Thanh toán thành công</h1>
        <p>
          VMarket đã nhận {formatMoney(payment.amount)} cho đơn {shortOrderId(orderId)}. Đơn đang
          chờ gian hàng xác nhận.
        </p>
        {!next && batch.length > 1 && (
          <>
            <p>Bạn đã thanh toán xong cả {batch.length} đơn của lần đặt hàng này.</p>
            <CheckoutOrderList orders={batch} />
          </>
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
            to={orderLink(orderId)}
          >
            Xem chi tiết đơn
          </Link>
          <Link className="button button--secondary" to={`/profile/payments/${payment.id}`}>
            Xem giao dịch
          </Link>
        </div>
      </section>
    )
  } else if (!isAwaitingPayment(payment) || expired) {
    body = (
      <section className="account-card payment-card is-warning" aria-live="polite">
        <h1>
          {expired ? 'Liên kết thanh toán đã hết hạn' : PAYMENT_STATUS_LABELS[payment.status]}
        </h1>
        <p>
          {payment.status === 'AWAITING_WEBHOOK' || payment.status === 'RECONCILING'
            ? 'Nếu bạn đã thanh toán, VMarket sẽ xác nhận ngay khi VNPay báo về. Không cần thanh toán lại.'
            : 'Đơn không thanh toán đúng hạn sẽ tự huỷ và hàng được trả lại kho.'}
        </p>
        <div className="account-actions">
          <Link className="button button--primary" to={orderLink(orderId)}>
            Xem đơn hàng
          </Link>
        </div>
      </section>
    )
  } else {
    const urgent = remaining != null && remaining < 60_000
    body = (
      <section className="account-card payment-card" aria-labelledby="payment-title">
        <h1 id="payment-title">Thanh toán qua VNPay</h1>
        <p>Bạn sẽ được chuyển sang cổng VNPay để thanh toán bằng thẻ, QR hoặc ví.</p>
        <div className="payment-card__grid">
          <div className="payment-card__timer">
            {remaining != null && (
              <p className={`payment-countdown${urgent ? ' is-urgent' : ''}`} role="timer">
                Liên kết hết hạn sau <strong>{formatCountdown(remaining)}</strong>
              </p>
            )}
          </div>
          <dl className="payment-facts">
            <div>
              <dt>Số tiền</dt>
              <dd className="payment-facts__amount">{formatMoney(payment.amount)}</dd>
            </div>
            <div>
              <dt>Đơn hàng</dt>
              <dd>{shortOrderId(orderId)}</dd>
            </div>
            <div>
              <dt>Mã giao dịch</dt>
              <dd>{paymentCode(payment)}</dd>
            </div>
            <div>
              <dt>Trạng thái</dt>
              <dd role="status">{PAYMENT_STATUS_LABELS[payment.status] || payment.status}</dd>
            </div>
          </dl>
        </div>
        <p className="payment-card__hint">
          Sau khi thanh toán xong VNPay sẽ đưa bạn về VMarket để xác nhận kết quả.
        </p>
        <div className="account-actions">
          {payment.checkoutUrl && (
            <a className="button button--primary" href={payment.checkoutUrl}>
              Thanh toán qua VNPay
            </a>
          )}
          <Link className="button button--secondary" to={orderLink(orderId)}>
            Thanh toán sau
          </Link>
        </div>
      </section>
    )
  }

  return (
    <main className="cart-page checkout-page">
      <ol className="checkout-steps" aria-label="Các bước đặt hàng">
        <li className="is-done">Giỏ hàng</li>
        <li className={paid ? 'is-done' : 'is-current'} aria-current={paid ? undefined : 'step'}>
          Thanh toán
        </li>
        <li className={paid ? 'is-current' : ''} aria-current={paid ? 'step' : undefined}>
          Hoàn tất
        </li>
      </ol>
      {body}
    </main>
  )
}
