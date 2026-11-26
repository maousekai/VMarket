import { useCallback, useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import DeliveryTrackingPanel from '../components/DeliveryTrackingPanel'
import Modal from '../components/Modal'
import useShipperGps, { GPS_PERMISSION_LABELS } from '../hooks/useShipperGps'
import { getApiError } from '../services/api/apiClient'
import {
  DELIVERY_STATUS_LABELS,
  isTerminalStatus,
  isTrackingStatus,
  listMyAssignments,
  parseOrderSnapshot,
  retryAssignment,
  shipperActions,
  updateAssignmentStatus,
} from '../services/api/deliveryApi'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { orderAddress, shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/delivery.css'

const TABS = [
  ['active', 'Đang xử lý'],
  ['done', 'Đã kết thúc'],
]

/** Chia se GPS cho chuyen dang chay; tu dung khi chuyen roi khoi trang thai theo doi. */
function GpsSharing({ assignment, onStopped }) {
  const tracking = isTrackingStatus(assignment.status)
  const [wanted, setWanted] = useState(true)
  const gps = useShipperGps(assignment.id, { enabled: tracking && wanted, onStopped })

  if (!tracking) return null

  function requestPermission() {
    navigator.geolocation?.getCurrentPosition(
      () => setWanted(true),
      () => {},
      { enableHighAccuracy: true, timeout: 20000 },
    )
  }

  return (
    <div className="shipper-gps" role="group" aria-label="Chia sẻ vị trí">
      <div>
        <strong>
          {gps.sharing ? 'Đang chia sẻ vị trí' : wanted ? 'Đang lấy vị trí…' : 'Đã tạm dừng'}
        </strong>
        <span className="delivery-track__muted">
          {GPS_PERMISSION_LABELS[gps.permission]}
          {gps.lastSentAt && ` · gửi lúc ${formatDateTime(gps.lastSentAt)}`}
        </span>
        {gps.error && (
          <span className="shipper-gps__error" role="alert">
            {gps.error}
          </span>
        )}
      </div>
      <div className="shipper-gps__actions">
        {gps.permission === 'prompt' && (
          <button className="account-button" type="button" onClick={requestPermission}>
            Cấp quyền vị trí
          </button>
        )}
        <button
          className="account-button account-button--secondary"
          type="button"
          aria-pressed={wanted}
          onClick={() => setWanted((value) => !value)}
        >
          {wanted ? 'Tạm dừng chia sẻ' : 'Tiếp tục chia sẻ'}
        </button>
      </div>
    </div>
  )
}

function DeliverDialog({ assignment, busy, error, onClose, onSubmit }) {
  const [amount, setAmount] = useState('')
  const cod = Number(assignment.codAmount || 0)
  const prepaid = cod === 0
  return (
    <Modal
      title="Xác nhận giao thành công"
      description={
        prepaid
          ? 'Đơn đã thanh toán trước, không thu tiền người nhận.'
          : `Thu hộ COD ${formatMoney(cod)}. Nhập đúng số tiền đã thu để xác nhận.`
      }
      onClose={onClose}
    >
      <form
        className="profile-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit({ codCollected: prepaid ? 0 : Number(amount) })
        }}
      >
        {!prepaid && (
          <AccountField
            label="Số tiền đã thu (VND)"
            required
            inputMode="numeric"
            data-autofocus
            value={amount}
            error={amount && Number(amount) !== cod ? 'Số tiền phải khớp số COD của đơn' : ''}
            onChange={(event) => setAmount(event.target.value.replace(/\D/g, ''))}
          />
        )}
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            className="account-button"
            type="submit"
            disabled={busy || (!prepaid && Number(amount) !== cod)}
          >
            {busy ? 'Đang xác nhận…' : 'Xác nhận đã giao'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Huỷ
          </button>
        </div>
      </form>
    </Modal>
  )
}

function FailDialog({ assignment, busy, error, onClose, onSubmit }) {
  const [reason, setReason] = useState('')
  const last = assignment.attempt >= 3
  return (
    <Modal
      title="Báo giao thất bại"
      description={
        last
          ? 'Đây là lần giao thứ 3: đơn sẽ chuyển sang hoàn hàng về gian hàng.'
          : `Lần giao ${assignment.attempt}/3. Bạn có thể giao lại sau.`
      }
      onClose={onClose}
    >
      <form
        className="profile-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit({ reason: reason.trim() })
        }}
      >
        <AccountField
          as="textarea"
          label="Lý do"
          required
          maxLength={255}
          data-autofocus
          placeholder="Ví dụ: không liên lạc được người nhận"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            className="account-button account-button--danger"
            type="submit"
            disabled={busy || !reason.trim()}
          >
            {busy ? 'Đang gửi…' : 'Xác nhận thất bại'}
          </button>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={onClose}
          >
            Huỷ
          </button>
        </div>
      </form>
    </Modal>
  )
}

function AssignmentCard({ assignment, onChanged, onStale }) {
  const order = parseOrderSnapshot(assignment.orderSnapshot)
  const [dialog, setDialog] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [showMap, setShowMap] = useState(false)
  const items = order.items || []

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      onChanged(await action())
      setDialog('')
    } catch (failure) {
      const apiError = getApiError(failure)
      setError(apiError.message)
      if (apiError.code === 'STALE_DELIVERY') onStale()
    } finally {
      setBusy(false)
    }
  }

  const changeStatus = (status, extra = {}) =>
    run(() =>
      updateAssignmentStatus(assignment.id, {
        status,
        expectedVersion: assignment.version,
        ...extra,
      }),
    )

  function onAction(action) {
    if (action.kind === 'deliver') setDialog('deliver')
    else if (action.kind === 'fail') setDialog('fail')
    else if (action.kind === 'retry') run(() => retryAssignment(assignment.id, assignment.version))
    else changeStatus(action.status)
  }

  return (
    <li className="account-card shipper-card">
      <div className="shipper-card__head">
        <div>
          <strong>Đơn {shortOrderId(assignment.orderId)}</strong>
          <span className="delivery-track__muted">
            Lần giao {assignment.attempt || 1}/3 · nhận {formatDateTime(assignment.createdAt)}
          </span>
        </div>
        <span className={`delivery-badge delivery-badge--${assignment.status.toLowerCase()}`}>
          {DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
        </span>
      </div>

      <dl className="shipper-card__info">
        <div>
          <dt>Người nhận</dt>
          <dd>
            {order.recipientName || '—'}
            {order.phone && (
              <>
                {' · '}
                <a href={`tel:${order.phone}`}>{order.phone}</a>
              </>
            )}
          </dd>
        </div>
        <div>
          <dt>Địa chỉ</dt>
          <dd>
            {orderAddress(order) ||
              [assignment.district, assignment.province].filter(Boolean).join(', ')}
          </dd>
        </div>
        {order.note && (
          <div>
            <dt>Ghi chú</dt>
            <dd>{order.note}</dd>
          </div>
        )}
        <div>
          <dt>Thu hộ</dt>
          <dd>
            {assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0
              ? `COD ${formatMoney(Number(assignment.codAmount))}`
              : 'Đã thanh toán trước'}
          </dd>
        </div>
        {items.length > 0 && (
          <div>
            <dt>Hàng hoá</dt>
            <dd>{items.reduce((sum, item) => sum + Number(item.quantity || 0), 0)} sản phẩm</dd>
          </div>
        )}
      </dl>

      <GpsSharing assignment={assignment} onStopped={onStale} />

      {error && !dialog && (
        <p className="account-notice account-notice--error" role="alert">
          {error}
        </p>
      )}

      <div className="account-actions shipper-card__actions">
        {shipperActions(assignment).map((action) => (
          <button
            key={action.label}
            type="button"
            className={`account-button${action.danger ? ' account-button--danger' : ''}`}
            disabled={busy}
            onClick={() => onAction(action)}
          >
            {action.label}
          </button>
        ))}
        {!isTerminalStatus(assignment.status) && (
          <button
            type="button"
            className="account-button account-button--secondary"
            aria-expanded={showMap}
            onClick={() => setShowMap((value) => !value)}
          >
            {showMap ? 'Ẩn bản đồ' : 'Xem bản đồ'}
          </button>
        )}
      </div>

      {showMap && <DeliveryTrackingPanel orderId={assignment.orderId} showHistory={false} />}

      {dialog === 'deliver' && (
        <DeliverDialog
          assignment={assignment}
          busy={busy}
          error={error}
          onClose={() => setDialog('')}
          onSubmit={(extra) => changeStatus('DELIVERED', extra)}
        />
      )}
      {dialog === 'fail' && (
        <FailDialog
          assignment={assignment}
          busy={busy}
          error={error}
          onClose={() => setDialog('')}
          onSubmit={(extra) => changeStatus('FAILED', extra)}
        />
      )}
    </li>
  )
}

/** Phan he shipper (PBL6-33): don duoc phan cong, xu ly giao va chia se GPS. */
export default function ShipperPage() {
  const [tab, setTab] = useState('active')
  const [state, setState] = useState({ status: 'loading', items: [], error: '' })
  const [reloadKey, setReloadKey] = useState(0)
  const reload = useCallback(() => setReloadKey((key) => key + 1), [])

  useEffect(() => {
    let current = true
    listMyAssignments()
      .then((items) => current && setState({ status: 'ready', items: items || [], error: '' }))
      .catch(
        (failure) =>
          current && setState({ status: 'error', items: [], error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [reloadKey])

  function onChanged(updated) {
    setState((previous) => ({
      ...previous,
      items: previous.items.map((item) => (item.id === updated.id ? updated : item)),
    }))
  }

  const visible = state.items.filter((item) =>
    tab === 'active' ? !isTerminalStatus(item.status) : isTerminalStatus(item.status),
  )
  const activeCount = state.items.filter((item) => !isTerminalStatus(item.status)).length

  return (
    <main className="account-page shipper-page">
      <div className="shipper-page__header">
        <div>
          <span className="account-eyebrow">Khu vực giao hàng</span>
          <h1>Đơn được phân công</h1>
          <p className="account-subtitle">
            Cập nhật trạng thái từng chuyến. Vị trí được chia sẻ tự động khi bạn đang giao và dừng
            khi chuyến kết thúc — giữ trang này mở trong lúc giao.
          </p>
        </div>
        <button className="account-button account-button--secondary" type="button" onClick={reload}>
          Làm mới
        </button>
      </div>

      <div className="admin-users__tabs" role="group" aria-label="Lọc chuyến giao">
        {TABS.map(([value, label]) => (
          <button
            key={value}
            type="button"
            className="admin-users__tab"
            aria-pressed={tab === value}
            onClick={() => setTab(value)}
          >
            {label}
            {value === 'active' && state.status === 'ready' && ` (${activeCount})`}
          </button>
        ))}
      </div>

      {state.status === 'loading' && <p className="delivery-track__muted">Đang tải…</p>}
      {state.status === 'error' && (
        <section className="account-card account-state">
          <p role="alert">{state.error}</p>
          <button className="account-button" type="button" onClick={reload}>
            Thử lại
          </button>
        </section>
      )}
      {state.status === 'ready' && visible.length === 0 && (
        <p className="account-card shipper-page__empty">
          {tab === 'active' ? 'Chưa có chuyến nào cần giao.' : 'Chưa có chuyến nào kết thúc.'}
        </p>
      )}
      {visible.length > 0 && (
        <ul className="shipper-list">
          {visible.map((assignment) => (
            <AssignmentCard
              key={assignment.id}
              assignment={assignment}
              onChanged={onChanged}
              onStale={reload}
            />
          ))}
        </ul>
      )}
    </main>
  )
}
