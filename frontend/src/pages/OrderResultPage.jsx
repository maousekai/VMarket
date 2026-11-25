import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { getOrder } from '../services/api/orderApi'
import CheckoutOrderList from '../components/CheckoutOrderList'
import '../components/account.css'
import '../components/cart.css'
import '../components/checkout.css'

const COPY = {
  success: {
    title: 'Đặt hàng thành công',
    text: 'Gian hàng sẽ xác nhận đơn trong thời gian sớm nhất. Bạn thanh toán khi nhận hàng.',
  },
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

/** Sau khi dat don COD (/checkout/success?orders=... hoac /:orderId cu). */
export default function OrderResultPage() {
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
      {ids.length ? (
        <CheckoutSuccess ids={ids} />
      ) : (
        <div className="account-card cart-empty">
          <h1>Không tìm thấy đơn vừa đặt</h1>
          <Link className="button button--primary" to="/profile/orders">
            Xem đơn mua
          </Link>
        </div>
      )}
    </main>
  )
}
