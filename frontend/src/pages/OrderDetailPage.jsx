import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import AccountLayout from '../components/AccountLayout'
import Modal from '../components/Modal'
import {
  OrderItemsList,
  OrderStatusBadge,
  OrderTimeline,
  ReturnStatusBadge,
} from '../components/OrderParts'
import ReturnRequestDialog from '../components/ReturnRequestDialog'
import { useShopLookup } from '../hooks/useCatalogLookup'
import { startPayosPayment } from '../order/payos'
import { getApiError } from '../services/api/apiClient'
import { DELIVERY_STATUS_LABELS, getDelivery } from '../services/api/deliveryApi'
import {
  cancelOrder,
  completeOrder,
  disputeReturn,
  getOrder,
  listOrderReturns,
} from '../services/api/orderApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import {
  canCancelOrder,
  canCompleteOrder,
  canRequestReturn,
  groupItemsByShop,
  orderAddress,
  PAYMENT_METHOD_LABELS,
  returnableItems,
  returnDeadline,
  shortOrderId,
} from '../utils/order'
import { imageUrlOrEmpty } from '../utils/shop'
import '../components/accountForms.css'
import '../components/orders.css'

const DISPUTE_MAX = 500

function DeliveryStatus({ orderId, status }) {
  const [delivery, setDelivery] = useState(null)
  useEffect(() => {
    if (!['PROCESSING', 'SHIPPED', 'DELIVERED'].includes(status)) return undefined
    let current = true
    getDelivery(orderId)
      .then((data) => current && setDelivery(data))
      .catch(() => current && setDelivery(null))
    return () => {
      current = false
    }
  }, [orderId, status])
  const deliveryStatus = delivery?.assignment?.status
  if (!deliveryStatus) return null
  return (
    <p className="order-delivery" role="status">
      <strong>Vận chuyển:</strong> {DELIVERY_STATUS_LABELS[deliveryStatus] || deliveryStatus}
    </p>
  )
}

function ReturnCard({ item, onDispute }) {
  return (
    <li className="order-return">
      <div className="order-return__head">
        <strong>Yêu cầu trả hàng · {formatDateTime(item.createdAt)}</strong>
        <ReturnStatusBadge status={item.status} />
      </div>
      <p>Lý do: {item.reason}</p>
      {item.evidenceUrls?.length > 0 && (
        <div className="order-return__evidence">
          {item.evidenceUrls.map((url) =>
            imageUrlOrEmpty(url) ? (
              <a key={url} href={url} target="_blank" rel="noreferrer">
                <img src={url} alt="Ảnh minh chứng" />
              </a>
            ) : null,
          )}
        </div>
      )}
      {item.sellerNote && <p>Phản hồi của gian hàng: {item.sellerNote}</p>}
      {item.adminNote && <p>Kết luận của sàn: {item.adminNote}</p>}
      {item.refundAmount != null && item.status === 'RESOLVED' && (
        <p>Số tiền hoàn: {formatMoney(item.refundAmount)}</p>
      )}
      {item.status === 'REJECTED' && (
        <button type="button" className="account-button" onClick={() => onDispute(item)}>
          Khiếu nại lên sàn
        </button>
      )}
    </li>
  )
}

function DisputeDialog({ item, onClose, onDone }) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  async function submit(event) {
    event.preventDefault()
    if (!reason.trim()) return setError('Vui lòng mô tả lý do khiếu nại.')
    setSaving(true)
    try {
      onDone(await disputeReturn(item.id, reason.trim()))
    } catch (failure) {
      setError(getApiError(failure).message)
      setSaving(false)
    }
  }
  return (
    <Modal
      title="Khiếu nại lên VMarket"
      description="Sàn sẽ xem xét bằng chứng của cả hai bên và đưa ra quyết định cuối cùng."
      onClose={onClose}
    >
      <form className="return-form" noValidate onSubmit={submit}>
        <label>
          <span>Lý do khiếu nại *</span>
          <textarea
            data-autofocus
            rows={4}
            maxLength={DISPUTE_MAX}
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
          <button type="submit" className="account-button" disabled={saving}>
            {saving ? 'Đang gửi…' : 'Gửi khiếu nại'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

export default function OrderDetailPage() {
  const { orderId } = useParams()
  const location = useLocation()
  const [state, setState] = useState({ status: 'loading', order: null, returns: [], error: '' })
  const [notice, setNotice] = useState(location.state?.message || '')
  const [actionError, setActionError] = useState('')
  const [busy, setBusy] = useState('')
  const [dialog, setDialog] = useState(null)

  const load = useCallback(async () => {
    const [order, returns] = await Promise.all([
      getOrder(orderId),
      listOrderReturns(orderId).catch(() => []),
    ])
    setState({ status: 'ready', order, returns, error: '' })
  }, [orderId])

  useEffect(() => {
    load().catch((failure) =>
      setState({ status: 'error', order: null, returns: [], error: getApiError(failure).message }),
    )
  }, [load])

  const order = state.order
  const shops = useShopLookup(order?.items.map((item) => item.shopId) || [])

  async function run(name, action, message) {
    if (busy) return
    setBusy(name)
    setActionError('')
    setNotice('')
    try {
      await action()
      if (message) setNotice(message)
      await load()
    } catch (failure) {
      setActionError(failure.response ? getApiError(failure).message : failure.message)
    } finally {
      setBusy('')
    }
  }

  let body
  if (state.status === 'loading') {
    body = (
      <p className="account-notice" role="status">
        Đang tải đơn hàng…
      </p>
    )
  } else if (state.status === 'error') {
    body = (
      <div className="account-card account-state">
        <p role="alert">{state.error}</p>
        <Link className="account-button" to="/profile/orders">
          Về danh sách đơn
        </Link>
      </div>
    )
  } else {
    const returnable = returnableItems(order, state.returns)
    const deadline = returnDeadline(order)
    body = (
      <>
        <div className="account-card order-detail__head">
          <div>
            <Link className="link-button" to="/profile/orders">
              ‹ Đơn mua
            </Link>
            <h1>Đơn {shortOrderId(order.id)}</h1>
            <small>Đặt lúc {formatDateTime(order.createdAt)}</small>
          </div>
          <OrderStatusBadge status={order.status} />
        </div>
        {notice && (
          <p className="account-notice account-notice--success" role="status">
            {notice}
          </p>
        )}
        {actionError && (
          <p className="account-notice account-notice--warning" role="alert">
            {actionError}
          </p>
        )}
        <section className="account-card order-detail__section" aria-label="Tiến trình">
          <OrderTimeline status={order.status} />
          <DeliveryStatus orderId={order.id} status={order.status} />
          {order.status === 'WAITING_PAYMENT' && order.paymentExpiresAt && (
            <p className="order-detail__hint">
              Thanh toán trước {formatDateTime(order.paymentExpiresAt)}, sau thời hạn này đơn sẽ tự
              huỷ.
            </p>
          )}
          {order.status === 'DELIVERED' && deadline && (
            <p className="order-detail__hint">
              Bạn có thể yêu cầu trả hàng đến {formatDateTime(deadline)}. Đơn tự hoàn tất sau thời
              hạn này.
            </p>
          )}
          <div className="order-detail__actions">
            {order.status === 'WAITING_PAYMENT' && order.paymentMethod === 'PAYOS' && (
              <button
                type="button"
                className="account-button"
                disabled={Boolean(busy)}
                onClick={() => run('pay', () => startPayosPayment(order.id))}
              >
                {busy === 'pay' ? 'Đang mở PayOS…' : 'Thanh toán ngay'}
              </button>
            )}
            {canCompleteOrder(order) && (
              <button
                type="button"
                className="account-button"
                disabled={Boolean(busy)}
                onClick={() =>
                  run('complete', () => completeOrder(order.id), 'Cảm ơn bạn! Đơn đã hoàn thành.')
                }
              >
                Đã nhận được hàng
              </button>
            )}
            {canRequestReturn(order, state.returns) && (
              <button
                type="button"
                className="account-button account-button--secondary"
                onClick={() => setDialog({ type: 'return', items: returnable })}
              >
                Yêu cầu trả hàng / hoàn tiền
              </button>
            )}
            {canCancelOrder(order) && (
              <button
                type="button"
                className="account-button account-button--danger"
                disabled={Boolean(busy)}
                onClick={() => setDialog({ type: 'cancel' })}
              >
                Huỷ đơn hàng
              </button>
            )}
          </div>
        </section>

        <div className="order-detail__grid">
          <section className="account-card order-detail__section" aria-labelledby="order-address">
            <h2 id="order-address">Địa chỉ nhận hàng</h2>
            <p>
              <strong>{order.recipientName}</strong> · {order.phone}
            </p>
            <p>{orderAddress(order)}</p>
            {order.note && <p className="order-detail__hint">Ghi chú: {order.note}</p>}
          </section>
          <section className="account-card order-detail__section" aria-labelledby="order-payment">
            <h2 id="order-payment">Thanh toán</h2>
            <p>{PAYMENT_METHOD_LABELS[order.paymentMethod] || order.paymentMethod}</p>
            <p className="order-detail__total">
              Tổng tiền hàng <strong>{formatMoney(order.totalAmount)}</strong>
            </p>
          </section>
        </div>

        {groupItemsByShop(order.items).map((group) => (
          <section
            key={group.shopId}
            className="account-card order-detail__section"
            aria-label={shops[group.shopId]?.value?.name || 'Gian hàng'}
          >
            <h2>
              <Link to={`/shops/${encodeURIComponent(group.shopId)}`}>
                {shops[group.shopId]?.value?.name || 'Gian hàng'}
              </Link>
            </h2>
            <OrderItemsList items={group.items} />
          </section>
        ))}

        {state.returns.length > 0 && (
          <section className="account-card order-detail__section" aria-labelledby="order-returns">
            <h2 id="order-returns">Yêu cầu trả hàng</h2>
            <ul className="order-returns">
              {state.returns.map((item) => (
                <ReturnCard
                  key={item.id}
                  item={item}
                  onDispute={(target) => setDialog({ type: 'dispute', item: target })}
                />
              ))}
            </ul>
          </section>
        )}
      </>
    )
  }

  return (
    <AccountLayout title="Đơn mua" current="/profile/orders">
      <section className="order-detail" aria-label="Chi tiết đơn hàng">
        {body}
      </section>
      {dialog?.type === 'cancel' && (
        <Modal
          title="Huỷ đơn hàng?"
          description="Hàng được trả lại kho; đơn đã thanh toán qua PayOS sẽ được hoàn tiền."
          onClose={() => setDialog(null)}
        >
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--secondary"
              data-autofocus
              onClick={() => setDialog(null)}
            >
              Giữ đơn
            </button>
            <button
              type="button"
              className="account-button account-button--danger"
              onClick={() => {
                setDialog(null)
                run('cancel', () => cancelOrder(order.id), 'Đã huỷ đơn hàng.')
              }}
            >
              Huỷ đơn
            </button>
          </div>
        </Modal>
      )}
      {dialog?.type === 'return' && (
        <ReturnRequestDialog
          order={order}
          items={dialog.items}
          onClose={() => setDialog(null)}
          onCreated={() => {
            setDialog(null)
            setNotice('Đã gửi yêu cầu trả hàng. Gian hàng sẽ phản hồi trong 48 giờ.')
            load()
          }}
        />
      )}
      {dialog?.type === 'dispute' && (
        <DisputeDialog
          item={dialog.item}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null)
            setNotice('Đã gửi khiếu nại. VMarket sẽ phân xử và thông báo kết quả.')
            load()
          }}
        />
      )}
    </AccountLayout>
  )
}
