import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import { OrderItemsList, OrderStatusBadge } from '../components/OrderParts'
import { useShopLookup } from '../hooks/useCatalogLookup'
import { getApiError } from '../services/api/apiClient'
import { listMyOrders } from '../services/api/orderApi'
import { useReloadOnNotification } from '../notifications/events'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { groupItemsByShop, shortOrderId } from '../utils/order'
import '../components/orders.css'

const TABS = [
  ['', 'Tất cả'],
  ['WAITING_PAYMENT', 'Chờ thanh toán'],
  ['PENDING', 'Chờ xác nhận'],
  ['PROCESSING', 'Đang chuẩn bị'],
  ['SHIPPED', 'Đang giao'],
  ['DELIVERED', 'Đã giao'],
  ['COMPLETED', 'Hoàn thành'],
  ['CANCELLED', 'Đã huỷ'],
]

function OrderCard({ order, shops }) {
  const shopNames = groupItemsByShop(order.items)
    .map((group) => shops[group.shopId]?.value?.name)
    .filter(Boolean)
  return (
    <article className="account-card order-card" aria-labelledby={`order-${order.id}`}>
      <header className="order-card__head">
        <div>
          <h2 id={`order-${order.id}`}>Đơn {shortOrderId(order.id)}</h2>
          <small>
            {formatDateTime(order.createdAt)}
            {shopNames.length > 0 && ` · ${shopNames.join(', ')}`}
          </small>
        </div>
        <OrderStatusBadge status={order.status} />
      </header>
      <OrderItemsList items={order.items} limit={2} />
      <footer className="order-card__foot">
        <span>
          Thành tiền: <strong>{formatMoney(order.totalAmount)}</strong>
        </span>
        <Link className="account-button" to={`/profile/orders/${encodeURIComponent(order.id)}`}>
          Xem chi tiết
        </Link>
      </footer>
    </article>
  )
}

export default function OrdersPage() {
  const [state, setState] = useState({ status: 'loading', orders: [], error: '' })
  const [reload, setReload] = useState(0)
  const [tab, setTab] = useState('')
  const [query, setQuery] = useState('')
  useReloadOnNotification(() => setReload((value) => value + 1))

  useEffect(() => {
    let current = true
    listMyOrders()
      .then((orders) => current && setState({ status: 'ready', orders, error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', orders: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [reload])

  const shops = useShopLookup(state.orders.flatMap((order) => order.items.map((i) => i.shopId)))
  const filtered = useMemo(() => {
    const term = query.trim().replace(/^#/, '').toUpperCase()
    return state.orders.filter(
      (order) => (!tab || order.status === tab) && (!term || order.id.toUpperCase().includes(term)),
    )
  }, [state.orders, tab, query])

  return (
    <AccountLayout title="Đơn mua" current="/profile/orders">
      <section className="orders-page" aria-labelledby="orders-title">
        <div className="orders-page__head">
          <div>
            <h1 id="orders-title">Đơn mua của tôi</h1>
            <p className="account-subtitle">Theo dõi trạng thái, huỷ đơn hoặc yêu cầu trả hàng.</p>
          </div>
          <input
            type="search"
            className="orders-page__search"
            aria-label="Tìm theo mã đơn"
            placeholder="Tìm theo mã đơn…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="order-tabs" role="group" aria-label="Lọc theo trạng thái">
          {TABS.map(([value, label]) => {
            const count = value
              ? state.orders.filter((order) => order.status === value).length
              : state.orders.length
            return (
              <button
                key={label}
                type="button"
                aria-pressed={tab === value}
                onClick={() => setTab(value)}
              >
                {label}
                {count > 0 && <span>{count}</span>}
              </button>
            )
          })}
        </div>
        {state.status === 'loading' && (
          <p className="account-notice" role="status">
            Đang tải đơn hàng…
          </p>
        )}
        {state.status === 'error' && (
          <div className="account-card account-state">
            <p role="alert">{state.error}</p>
            <button
              className="account-button"
              type="button"
              onClick={() => setReload((value) => value + 1)}
            >
              Thử lại
            </button>
          </div>
        )}
        {state.status === 'ready' && filtered.length === 0 && (
          <div className="account-card account-state">
            <p>{state.orders.length ? 'Không có đơn phù hợp.' : 'Bạn chưa có đơn hàng nào.'}</p>
            {!state.orders.length && (
              <Link className="account-button" to="/">
                Bắt đầu mua sắm
              </Link>
            )}
          </div>
        )}
        <div className="order-list">
          {filtered.map((order) => (
            <OrderCard key={order.id} order={order} shops={shops} />
          ))}
        </div>
      </section>
    </AccountLayout>
  )
}
