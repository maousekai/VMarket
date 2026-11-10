import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import QRCode from 'qrcode'
import { getApiError } from '../services/api/apiClient'
import { syncPayment } from '../services/api/paymentApi'
import CheckoutOrderList from '../components/CheckoutOrderList'
import {
  clearPayosCheckout,
  findNextUnpaid,
  loadCheckoutOrders,
  preparePayosPayment,
} from '../order/payos'
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

function QrImage({ value }) {
  const [src, setSrc] = useState('')
  useEffect(() => {
    let current = true
    QRCode.toDataURL(value, { width: 240, margin: 1, errorCorrectionLevel: 'M' })
      .then((url) => current && setSrc(url))
      .catch(() => current && setSrc(''))
    return () => {
      current = false
    }
  }, [value])
  if (!src) return <div className="payment-qr__placeholder" aria-hidden="true" />
  return <img className="payment-qr__image" src={src} alt="Mã VietQR thanh toán đơn hàng" />
}

/**
 * Man thanh toan PayOS: QR VietQR + dem nguoc han thanh toan. Trang thai duoc hoi lai Payment
 * Service moi vai giay (sync hoi thang PayOS nen khong can webhook o local).
 */
export default function PaymentPage() {
  const { orderId } = useParams()
  const navigate = useNavigate()
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
    preparePayosPayment(orderId)
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
        const latest = await syncPayment(paymentId)
        if (current) setState((value) => ({ ...value, payment: latest }))
      } catch {
        // Keep the QR on screen; the next tick retries.
      }
    }, PAYMENT_POLL_MS)
    return () => {
      current = false
      clearInterval(timer)
    }
  }, [paymentId, waiting])

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
      clearPayosCheckout()
    })
    return () => {
      current = false
    }
  }, [paid, orderId])

  let body
  if (state.status === 'preparing') {
    body = (
      <p className="account-notice" role="status">
        Đang tạo mã thanh toán…
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
        <h1>{expired ? 'Mã thanh toán đã hết hạn' : PAYMENT_STATUS_LABELS[payment.status]}</h1>
        <p>
          {payment.status === 'AWAITING_WEBHOOK' || payment.status === 'RECONCILING'
            ? 'Nếu bạn đã chuyển khoản, VMarket sẽ xác nhận ngay khi ngân hàng báo về. Không cần chuyển lại.'
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
        <h1 id="payment-title">Quét mã để thanh toán</h1>
        <p>Mở ứng dụng ngân hàng hoặc ví điện tử, quét mã VietQR bên dưới.</p>
        <div className="payment-card__grid">
          <div className="payment-qr">
            {payment.qrAvailable ? (
              <QrImage value={payment.qrCode} />
            ) : (
              <p className="payment-qr__fallback">
                Mã QR chưa sẵn sàng, hãy mở trang thanh toán PayOS.
              </p>
            )}
            {remaining != null && (
              <p className={`payment-countdown${urgent ? ' is-urgent' : ''}`} role="timer">
                Mã hết hạn sau <strong>{formatCountdown(remaining)}</strong>
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
          Trang tự cập nhật khi thanh toán thành công, bạn không cần tải lại.
        </p>
        <div className="account-actions">
          {payment.checkoutUrl && (
            <a className="button button--secondary" href={payment.checkoutUrl}>
              Mở trang thanh toán PayOS
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
