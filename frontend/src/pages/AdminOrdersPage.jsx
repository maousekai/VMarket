import { useEffect, useState } from 'react'
import Modal from '../components/Modal'
import { OrderItemsList, OrderStatusBadge, OrderTimeline } from '../components/OrderParts'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import { adminCancelOrder, getAdminOrder, listAdminOrders } from '../services/api/orderApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { orderAddress, PAYMENT_METHOD_LABELS, shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/adminUsers.css'
import '../components/adminShops.css'
import '../components/adminCatalog.css'
import '../components/orders.css'

const PAGE_SIZE = 10
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
const FINAL = ['CANCELLED', 'COMPLETED']

function OrderDrawer({ orderId, onClose, onCancelled }) {
  const [state, setState] = useState({ status: 'loading', order: null, error: '' })
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let current = true
    getAdminOrder(orderId)
      .then((order) => current && setState({ status: 'ready', order, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', order: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [orderId])

  async function cancel(event) {
    event.preventDefault()
    if (!reason.trim()) return setError('Vui lòng nhập lý do huỷ đơn.')
    setSaving(true)
    setError('')
    try {
      const order = await adminCancelOrder(orderId, reason.trim())
      setState({ status: 'ready', order, error: '' })
      setCancelling(false)
      onCancelled(order)
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setSaving(false)
    }
  }

  const order = state.order
  return (
    <Modal variant="drawer" title={`Chi tiết đơn ${shortOrderId(orderId)}`} onClose={onClose}>
      {state.status === 'loading' && <p role="status">Đang tải…</p>}
      {state.status === 'error' && <p role="alert">{state.error}</p>}
      {order && (
        <div className="admin-order">
          <div className="admin-order__row">
            <OrderStatusBadge status={order.status} />
            <small>Đặt lúc {formatDateTime(order.createdAt)}</small>
          </div>
          <OrderTimeline status={order.status} />
          <dl className="admin-order__facts">
            <div>
              <dt>Người nhận</dt>
              <dd>
                {order.recipientName} · {order.phone}
              </dd>
            </div>
            <div>
              <dt>Địa chỉ</dt>
              <dd>{orderAddress(order)}</dd>
            </div>
            <div>
              <dt>Thanh toán</dt>
              <dd>{PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod}</dd>
            </div>
            <div>
              <dt>Tổng tiền</dt>
              <dd>{formatMoney(order.totalAmount)}</dd>
            </div>
            {order.note && (
              <div>
                <dt>Ghi chú</dt>
                <dd>{order.note}</dd>
              </div>
            )}
          </dl>
          <OrderItemsList items={order.items} />
          {!FINAL.includes(order.status) &&
            (cancelling ? (
              <form className="admin-catalog__form" noValidate onSubmit={cancel}>
                <label>
                  <span>Lý do huỷ *</span>
                  <textarea
                    rows={3}
                    maxLength={500}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                  />
                </label>
                {error && (
                  <p className="form-message form-message--error" role="alert">
                    {error}
                  </p>
                )}
                <div className="account-actions">
                  <button
                    type="button"
                    className="account-button account-button--secondary"
                    onClick={() => setCancelling(false)}
                  >
                    Quay lại
                  </button>
                  <button
                    type="submit"
                    className="account-button account-button--danger"
                    disabled={saving}
                  >
                    {saving ? 'Đang huỷ…' : 'Xác nhận huỷ đơn'}
                  </button>
                </div>
              </form>
            ) : (
              <button
                type="button"
                className="account-button account-button--danger"
                onClick={() => setCancelling(true)}
              >
                Huỷ đơn hàng
              </button>
            ))}
        </div>
      )}
    </Modal>
  )
}

export default function AdminOrdersPage() {
  const [filters, setFilters] = useState({ status: '', userId: '', page: 0 })
  const [draft, setDraft] = useState('')
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [reload, setReload] = useState(0)
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading' }))
    listAdminOrders({ ...filters, size: PAGE_SIZE })
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch(
        (failure) =>
          current &&
          setState({ status: 'error', result: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [filters, reload])

  const result = state.result
  return (
    <section className="admin-catalog" aria-labelledby="admin-orders-heading">
      <div className="admin-users__header">
        <div>
          <span className="account-eyebrow">Vận hành sàn</span>
          <h1 id="admin-orders-heading">Giám sát đơn hàng</h1>
          <p className="account-subtitle">
            Theo dõi đơn toàn sàn, xem chi tiết và huỷ đơn khi có sự cố.
          </p>
        </div>
        {result && (
          <div className="admin-shops__summary">
            <strong>{result.totalElements}</strong>
            <span>đơn hàng</span>
          </div>
        )}
      </div>
      <div className="admin-users__toolbar">
        <div className="admin-users__tabs" role="group" aria-label="Lọc theo trạng thái">
          {TABS.map(([value, label]) => (
            <button
              key={label}
              type="button"
              className="admin-users__tab"
              aria-pressed={filters.status === value}
              onClick={() => setFilters({ ...filters, status: value, page: 0 })}
            >
              {label}
            </button>
          ))}
        </div>
        <form
          className="admin-users__search"
          role="search"
          noValidate
          onSubmit={(event) => {
            event.preventDefault()
            setFilters({ ...filters, userId: draft.trim(), page: 0 })
          }}
        >
          <input
            type="search"
            aria-label="Lọc theo mã người mua"
            placeholder="Mã người mua (userId)…"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          <button className="account-button" type="submit">
            Lọc
          </button>
        </form>
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
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
      {state.status === 'loading' && !result && (
        <p className="account-notice" role="status">
          Đang tải đơn hàng…
        </p>
      )}
      {result && state.status !== 'error' && (
        <>
          {result.content.length === 0 ? (
            <p className="admin-users__empty">Không có đơn hàng phù hợp.</p>
          ) : (
            <div
              className={`admin-users__table-wrap${state.status === 'loading' ? ' is-loading' : ''}`}
            >
              <table className="admin-table">
                <caption className="visually-hidden">Đơn hàng toàn sàn</caption>
                <thead>
                  <tr>
                    <th scope="col">Mã đơn</th>
                    <th scope="col">Người nhận</th>
                    <th scope="col">Sản phẩm</th>
                    <th scope="col">Tổng tiền</th>
                    <th scope="col">Thanh toán</th>
                    <th scope="col">Ngày đặt</th>
                    <th scope="col">Trạng thái</th>
                  </tr>
                </thead>
                <tbody>
                  {result.content.map((order) => (
                    <tr key={order.id}>
                      <td>
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => setSelected(order.id)}
                        >
                          {shortOrderId(order.id)}
                        </button>
                      </td>
                      <td>
                        {order.recipientName}
                        <small className="admin-cell__sub">{order.phone}</small>
                      </td>
                      <td>{order.items?.reduce((sum, item) => sum + item.quantity, 0)}</td>
                      <td>{formatMoney(order.totalAmount)}</td>
                      <td>{order.paymentMethod}</td>
                      <td>{formatDateTime(order.createdAt)}</td>
                      <td>
                        <OrderStatusBadge status={order.status} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            onChange={(page) => setFilters({ ...filters, page })}
          />
        </>
      )}
      {selected && (
        <OrderDrawer
          orderId={selected}
          onClose={() => setSelected(null)}
          onCancelled={(order) => {
            setNotice(`Đã huỷ đơn ${shortOrderId(order.id)}.`)
            setReload((value) => value + 1)
          }}
        />
      )}
    </section>
  )
}
