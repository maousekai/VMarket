import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { confirmVnpayReturn } from '../services/api/paymentApi'
import CheckoutOrderList from '../components/CheckoutOrderList'
import { clearVnpayCheckout, findNextUnpaid, loadCheckoutOrders } from '../order/vnpay'
import { formatMoney } from '../utils/formatMoney'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/cart.css'
import '../components/checkout.css'

// vnp_ResponseCode 24: nguoi mua bam huy tren trang VNPay.
const CANCELLED_CODE = '24'

const orderLink = (orderId) => `/profile/orders/${encodeURIComponent(orderId)}`

function resultOf(payment, search) {
  if (payment.paidAt || payment.status === 'SUCCEEDED') {
    return {
      kind: 'success',
      title: 'Thanh toán thành công',
      text: 'VMarket đã nhận thanh toán, đơn đang chờ gian hàng xác nhận.',
    }
  }
  if (new URLSearchParams(search).get('vnp_ResponseCode') === CANCELLED_CODE) {
    return {
      kind: 'warning',
      title: 'Bạn đã huỷ thanh toán',
      text: 'Đơn vẫn được giữ trong thời gian thanh toán. Bạn có thể thanh toán lại hoặc huỷ đơn trong Đơn mua.',
    }
  }
  return {
    kind: 'warning',
    title: 'Thanh toán không thành công',
    text: 'VNPay chưa ghi nhận thanh toán. Đơn vẫn được giữ trong thời gian thanh toán, bạn có thể thử lại.',
  }
}

/** VNPay chuyen nguoi mua ve /payment/vnpay-return?vnp_...: xac thuc chu ky roi hien ket qua. */
export default function VnpayReturnPage() {
  const { search } = useLocation()
  const [state, setState] = useState({ status: 'loading', payment: null, error: '' })
  const [next, setNext] = useState(null)
  const [batch, setBatch] = useState([])

  useEffect(() => {
    let current = true
    confirmVnpayReturn(search)
      .then(async (payment) => {
        if (!current) return
        setState({ status: 'ready', payment, error: '' })
        const following = await findNextUnpaid(payment.orderId)
        if (!current) return
        setNext(following)
        // Het don cho thanh toan thi liet ke du cac don roi xoa hang doi.
        if (!following && (payment.paidAt || payment.status === 'SUCCEEDED')) {
          const orders = await loadCheckoutOrders()
          if (!current) return
          setBatch(orders)
          clearVnpayCheckout()
        }
      })
      .catch((failure) => {
        if (current) {
          setState({ status: 'error', payment: null, error: getApiError(failure).message })
        }
      })
    return () => {
      current = false
    }
  }, [search])

  const payment = state.payment
  const result = payment ? resultOf(payment, search) : null

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
        className={`account-card order-result${result?.kind === 'warning' ? ' is-warning' : ''}`}
        aria-live="polite"
      >
        {state.status === 'loading' && <p role="status">Đang xác nhận thanh toán với VNPay…</p>}
        {state.status === 'error' && (
          <>
            <h1>Không xác nhận được thanh toán</h1>
            <p role="alert">{state.error}</p>
            <div className="account-actions">
              <Link className="button button--primary" to="/profile/orders">
                Xem đơn mua
              </Link>
            </div>
          </>
        )}
        {result && (
          <>
            <h1>{result.title}</h1>
            <p>{result.text}</p>
            {batch.length > 1 ? (
              <CheckoutOrderList orders={batch} label="Các đơn của lần đặt hàng này" />
            ) : (
              <p>
                Đơn {shortOrderId(payment.orderId)} · {formatMoney(payment.amount)}
              </p>
            )}
            {next && (
              <div className="order-result__next">
                <p>
                  Bạn còn đơn {shortOrderId(next.id)} ({formatMoney(next.totalAmount)}) của gian
                  hàng khác chưa thanh toán.
                </p>
                <Link
                  className="button button--primary"
                  to={`/payment/${encodeURIComponent(next.id)}`}
                  state={{ autoRedirect: true }}
                  replace
                >
                  Thanh toán đơn tiếp theo
                </Link>
              </div>
            )}
            <div className="account-actions">
              <Link
                className={`button ${next ? 'button--secondary' : 'button--primary'}`}
                to={orderLink(payment.orderId)}
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
