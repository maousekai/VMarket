import { Link } from 'react-router-dom'
import { formatMoney } from '../utils/formatMoney'
import { ORDER_STATUS_LABELS, shortOrderId } from '../utils/order'
import './checkout.css'

/** Cac don tao ra tu mot lan dat hang (moi gian hang mot don). */
export default function CheckoutOrderList({ orders, label = 'Các đơn vừa đặt' }) {
  if (!orders?.length) return null
  return (
    <ul className="order-result__list" aria-label={label}>
      {orders.map((order) => (
        <li key={order.id}>
          <span>
            <strong>Đơn {shortOrderId(order.id)}</strong>
            <small>{ORDER_STATUS_LABELS[order.status] || order.status}</small>
          </span>
          <strong>{formatMoney(order.totalAmount)}</strong>
          <Link to={`/profile/orders/${encodeURIComponent(order.id)}`}>Xem đơn</Link>
        </li>
      ))}
    </ul>
  )
}
