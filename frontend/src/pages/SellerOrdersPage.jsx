import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Modal from '../components/Modal'
import { OrderItemsList, OrderStatusBadge, ReturnStatusBadge } from '../components/OrderParts'
import Pagination from '../components/Pagination'
import { getApiError } from '../services/api/apiClient'
import {
  DELIVERY_STATUS_LABELS,
  getDelivery,
  handoffOrder,
  simulateDelivery,
} from '../services/api/deliveryApi'
import {
  acceptReturn,
  confirmReturnReceipt,
  confirmSellerOrder,
  listSellerOrders,
  listSellerReturns,
  rejectReturn,
} from '../services/api/orderApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { orderAddress, PAYMENT_METHOD_LABELS, shortOrderId } from '../utils/order'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/seller.css'
import '../components/sellerProducts.css'
import '../components/orders.css'

const PAGE_SIZE = 10
const SELLER_RESPONSE_HOURS = 48
const ORDER_TABS = [
  ['PENDING', 'Chờ xác nhận'],
  ['PROCESSING', 'Đang chuẩn bị'],
  ['SHIPPED', 'Đang giao'],
  ['DELIVERED', 'Đã giao'],
  ['COMPLETED', 'Hoàn thành'],
  ['CANCELLED', 'Đã huỷ'],
  ['', 'Tất cả'],
]
const RETURN_TABS = [
  ['PENDING', 'Chờ xử lý'],
  ['APPROVED', 'Chờ nhận hàng trả'],
  ['DISPUTED', 'Đang khiếu nại'],
  ['', 'Tất cả'],
]
const HANDOFF_STATUSES = ['PENDING', 'PROCESSING']
// TẠM THỜI (dev): chuyến giao còn có thể giả lập tới "Đã giao".
const SIMULATABLE_DELIVERY = ['WAITING_ASSIGNMENT', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT']
const SIMULATION_SETTLE_MS = 1500

/** Trang thai chuyen giao cua phan hang thuoc shop (Delivery Service, goc nhin seller). */
function useDelivery(order, version) {
  const [delivery, setDelivery] = useState({ status: 'idle', data: null })
  const relevant =
    (HANDOFF_STATUSES.includes(order.status) && order.shopConfirmed) || order.status === 'SHIPPED'
  useEffect(() => {
    if (!relevant) return undefined
    let current = true
    getDelivery(order.id)
      .then((data) => current && setDelivery({ status: 'ready', data }))
      .catch(() => current && setDelivery({ status: 'none', data: null }))
    return () => {
      current = false
    }
  }, [order.id, relevant, version])
  return delivery
}

function SellerOrderCard({ order, onChanged }) {
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  const delivery = useDelivery(order, version)
  const handedOff = (delivery.data?.pickups || []).length > 0
  const deliveryStatus = delivery.data?.assignment?.status

  async function run(name, action, message) {
    setBusy(name)
    setError('')
    try {
      await action()
      setVersion((value) => value + 1)
      onChanged(message)
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy('')
    }
  }

  return (
    <article className="account-card order-card" aria-labelledby={`seller-order-${order.id}`}>
      <header className="order-card__head">
        <div>
          <h2 id={`seller-order-${order.id}`}>Đơn {shortOrderId(order.id)}</h2>
          <small>
            {formatDateTime(order.createdAt)} ·{' '}
            {PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod}
          </small>
        </div>
        <OrderStatusBadge status={order.status} />
      </header>
      <div className="seller-order__buyer">
        <strong>
          {order.recipientName} · {order.phone}
        </strong>
        <span>{orderAddress(order)}</span>
        {order.note && <span>Ghi chú: {order.note}</span>}
      </div>
      <OrderItemsList items={order.items} />
      {deliveryStatus && (
        <p className="order-delivery seller-order__delivery" role="status">
          <strong>Vận chuyển:</strong> {DELIVERY_STATUS_LABELS[deliveryStatus] || deliveryStatus}
        </p>
      )}
      {error && (
        <p className="form-message form-message--error seller-order__error" role="alert">
          {error}
        </p>
      )}
      <footer className="order-card__foot">
        <span>
          Tiền hàng của shop: <strong>{formatMoney(order.shopTotalAmount)}</strong>
        </span>
        <div className="seller-order__actions">
          {order.status === 'PENDING' && !order.shopConfirmed && (
            <button
              type="button"
              className="account-button"
              disabled={Boolean(busy)}
              onClick={() =>
                run(
                  'confirm',
                  () => confirmSellerOrder(order.id),
                  `Đã xác nhận đơn ${shortOrderId(order.id)}.`,
                )
              }
            >
              {busy === 'confirm' ? 'Đang xác nhận…' : 'Xác nhận đơn'}
            </button>
          )}
          {order.status === 'PENDING' && order.shopConfirmed && !handedOff && (
            <span className="seller-order__hint">Đã xác nhận</span>
          )}
          {HANDOFF_STATUSES.includes(order.status) &&
            order.shopConfirmed &&
            delivery.status !== 'idle' &&
            !handedOff && (
              <button
                type="button"
                className="account-button account-button--secondary"
                disabled={Boolean(busy)}
                onClick={() =>
                  run(
                    'handoff',
                    () => handoffOrder(order.id),
                    `Đã bàn giao đơn ${shortOrderId(order.id)} cho đơn vị vận chuyển.`,
                  )
                }
              >
                {busy === 'handoff' ? 'Đang bàn giao…' : 'Bàn giao vận chuyển'}
              </button>
            )}
          {handedOff && HANDOFF_STATUSES.includes(order.status) && (
            <span className="seller-order__hint">Đã bàn giao</span>
          )}
          {/* TẠM THỜI (dev): chưa có giao diện điều phối / shipper trên web. Xoá nút này và
              simulateDelivery khi UI shipper hoàn thành. */}
          {handedOff && SIMULATABLE_DELIVERY.includes(deliveryStatus) && (
            <button
              type="button"
              className="account-button seller-order__simulate"
              disabled={Boolean(busy)}
              onClick={() =>
                run(
                  'simulate',
                  async () => {
                    await simulateDelivery(order.id)
                    // Order Service cập nhật "Đã giao" qua sự kiện, chờ một nhịp rồi tải lại.
                    await new Promise((resolve) => setTimeout(resolve, SIMULATION_SETTLE_MS))
                  },
                  `Đã giả lập giao thành công đơn ${shortOrderId(order.id)}.`,
                )
              }
            >
              {busy === 'simulate' ? 'Đang giả lập…' : 'Giả lập giao hàng'}
            </button>
          )}
        </div>
      </footer>
    </article>
  )
}

function RejectDialog({ item, onClose, onDone }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  async function submit(event) {
    event.preventDefault()
    if (!reason.trim()) return setError('Vui lòng nhập lý do từ chối.')
    setSaving(true)
    try {
      onDone(await rejectReturn(item.id, reason.trim()))
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }
  return (
    <Modal
      title="Từ chối yêu cầu trả hàng"
      description="Người mua có thể khiếu nại lên sàn nếu không đồng ý với lý do của bạn."
      onClose={onClose}
    >
      <form className="return-form" noValidate onSubmit={submit}>
        <label>
          <span>Lý do từ chối *</span>
          <textarea
            data-autofocus
            rows={4}
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
            onClick={onClose}
          >
            Huỷ
          </button>
          <button type="submit" className="account-button account-button--danger" disabled={saving}>
            {saving ? 'Đang gửi…' : 'Từ chối'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function SellerReturnCard({ item, onChanged, onReject }) {
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const deadline = new Date(new Date(item.createdAt).getTime() + SELLER_RESPONSE_HOURS * 3600_000)

  async function run(name, action, message) {
    setBusy(name)
    setError('')
    try {
      await action()
      onChanged(message)
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      setBusy('')
    }
  }

  return (
    <article className="account-card order-card" aria-labelledby={`return-${item.id}`}>
      <header className="order-card__head">
        <div>
          <h2 id={`return-${item.id}`}>Trả hàng cho đơn {shortOrderId(item.orderId)}</h2>
          <small>
            Gửi lúc {formatDateTime(item.createdAt)}
            {item.status === 'PENDING' && ` · Phản hồi trước ${formatDateTime(deadline)}`}
          </small>
        </div>
        <ReturnStatusBadge status={item.status} />
      </header>
      <div className="seller-order__buyer">
        <span>Lý do: {item.reason}</span>
        {item.sellerNote && <span>Phản hồi của bạn: {item.sellerNote}</span>}
        {item.adminNote && <span>Kết luận của sàn: {item.adminNote}</span>}
      </div>
      {item.evidenceUrls?.length > 0 && (
        <div className="order-return__evidence seller-order__evidence">
          {item.evidenceUrls.map((url) =>
            imageUrlOrEmpty(url) ? (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                <img src={url} alt="Ảnh minh chứng" />
              </a>
            ) : null,
          )}
        </div>
      )}
      <OrderItemsList items={item.items || []} />
      {error && (
        <p className="form-message form-message--error seller-order__error" role="alert">
          {error}
        </p>
      )}
      <footer className="order-card__foot">
        <span>
          Hoàn tiền dự kiến:{' '}
          <strong>
            {formatMoney(
              item.refundAmount ??
                (item.items || []).reduce((sum, line) => sum + Number(line.lineTotal), 0),
            )}
          </strong>
        </span>
        <div className="seller-order__actions">
          {item.status === 'PENDING' && (
            <>
              <button
                type="button"
                className="account-button account-button--secondary"
                disabled={Boolean(busy)}
                onClick={() => onReject(item)}
              >
                Từ chối
              </button>
              <button
                type="button"
                className="account-button"
                disabled={Boolean(busy)}
                onClick={() =>
                  run(
                    'accept',
                    () => acceptReturn(item.id),
                    'Đã chấp nhận yêu cầu. Người mua sẽ gửi hàng về cho bạn.',
                  )
                }
              >
                Chấp nhận trả hàng
              </button>
            </>
          )}
          {item.status === 'APPROVED' && (
            <button
              type="button"
              className="account-button"
              disabled={Boolean(busy)}
              onClick={() =>
                run(
                  'receipt',
                  () => confirmReturnReceipt(item.id),
                  'Đã xác nhận nhận hàng trả. Hệ thống sẽ hoàn tiền cho người mua.',
                )
              }
            >
              Đã nhận hàng trả
            </button>
          )}
        </div>
      </footer>
    </article>
  )
}

export default function SellerOrdersPage() {
  const [params, setParams] = useSearchParams()
  const view = params.get('view') === 'returns' ? 'returns' : 'orders'
  const tabs = view === 'returns' ? RETURN_TABS : ORDER_TABS
  const status = params.has('status') ? params.get('status') : tabs[0][0]
  const page = Math.max(Number.parseInt(params.get('page') || '0', 10) || 0, 0)
  const [state, setState] = useState({ status: 'loading', result: null, error: '' })
  const [reload, setReload] = useState(0)
  const [notice, setNotice] = useState('')
  const [rejecting, setRejecting] = useState(null)

  const load = useCallback(
    () =>
      view === 'returns'
        ? listSellerReturns({ status, page, size: PAGE_SIZE })
        : listSellerOrders({ status, page, size: PAGE_SIZE }),
    [view, status, page],
  )

  useEffect(() => {
    let current = true
    setState((previous) => ({ ...previous, status: 'loading' }))
    load()
      .then((result) => current && setState({ status: 'ready', result, error: '' }))
      .catch((failure) => {
        if (!current) return
        const apiError = getApiError(failure)
        setState({
          status: 'error',
          result: null,
          error:
            apiError.status === 403
              ? 'Gian hàng cần được duyệt trước khi xử lý đơn hàng.'
              : apiError.message,
        })
      })
    return () => {
      current = false
    }
  }, [load, reload])

  const update = (changes) => {
    const next = new URLSearchParams(params)
    Object.entries(changes).forEach(([key, value]) =>
      value === null ? next.delete(key) : next.set(key, value),
    )
    setParams(next)
  }
  const changed = (message) => {
    setNotice(message)
    setReload((value) => value + 1)
  }
  const result = state.result

  return (
    <section className="seller-page" aria-labelledby="seller-orders-title">
      <div className="seller-page__header seller-page__header--row">
        <div>
          <span className="account-eyebrow">Kênh người bán</span>
          <h1 id="seller-orders-title">
            {view === 'returns' ? 'Yêu cầu trả hàng' : 'Quản lý đơn hàng'}
          </h1>
        </div>
        <div className="seller-tabs" role="group" aria-label="Chế độ xem">
          <button
            type="button"
            aria-pressed={view === 'orders'}
            onClick={() => update({ view: null, status: null, page: null })}
          >
            Đơn hàng
          </button>
          <button
            type="button"
            aria-pressed={view === 'returns'}
            onClick={() => update({ view: 'returns', status: null, page: null })}
          >
            Yêu cầu trả hàng
          </button>
        </div>
      </div>
      <div className="order-tabs" role="group" aria-label="Lọc theo trạng thái">
        {tabs.map(([value, label]) => (
          <button
            key={label}
            type="button"
            aria-pressed={status === value}
            onClick={() => update({ status: value, page: null })}
          >
            {label}
          </button>
        ))}
      </div>
      {notice && (
        <p className="account-notice account-notice--success" role="status">
          {notice}
        </p>
      )}
      {state.status === 'loading' && !result && (
        <p className="account-notice" role="status">
          Đang tải…
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
      {result && state.status !== 'error' && (
        <>
          {result.content.length === 0 ? (
            <div className="account-card account-state">
              <p>
                {view === 'returns'
                  ? 'Không có yêu cầu trả hàng ở trạng thái này.'
                  : 'Không có đơn hàng ở trạng thái này.'}
              </p>
            </div>
          ) : (
            <div className={`order-list${state.status === 'loading' ? ' is-loading' : ''}`}>
              {view === 'returns'
                ? result.content.map((item) => (
                    <SellerReturnCard
                      key={item.id}
                      item={item}
                      onChanged={changed}
                      onReject={setRejecting}
                    />
                  ))
                : result.content.map((order) => (
                    <SellerOrderCard key={order.id} order={order} onChanged={changed} />
                  ))}
            </div>
          )}
          <Pagination
            page={result.page}
            totalPages={result.totalPages}
            onChange={(next) => update({ page: String(next) })}
          />
        </>
      )}
      {rejecting && (
        <RejectDialog
          item={rejecting}
          onClose={() => setRejecting(null)}
          onDone={() => {
            setRejecting(null)
            changed('Đã từ chối yêu cầu trả hàng.')
          }}
        />
      )}
    </section>
  )
}
