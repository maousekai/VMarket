import { useCallback, useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import DeliveryTrackingPanel from '../components/DeliveryTrackingPanel'
import Modal from '../components/Modal'
import useShipperGps, { GPS_PERMISSION_LABELS } from '../hooks/useShipperGps'
import { getApiError } from '../services/api/apiClient'
import {
  confirmPickup,
  confirmReturn,
  endShift,
  getShift,
  isTerminalStatus,
  listMyAssignments,
  retryAssignment,
  shipperActions,
  startShift,
  updateAssignmentStatus,
} from '../services/api/deliveryApi'
import {
  DELIVERY_STATUS_LABELS,
  FAILURE_REASONS,
  directionsUrl,
  failureReason,
  isReturnStatus,
  telUrl,
} from '../utils/deliveryStatus'
import { formatDateTime } from '../utils/formatDate'
import { formatMoney } from '../utils/formatMoney'
import { shortOrderId } from '../utils/order'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/delivery.css'

const TABS = [
  ['active', 'Đang xử lý'],
  ['done', 'Đã kết thúc'],
]
const PAGE_SIZE = 20
// Tu lam moi de chuyen moi duoc phan cong hien len ma shipper khong phai bam "Lam moi".
const AUTO_REFRESH_MS = 30_000
const groupThousands = (digits) => (digits ? Number(digits).toLocaleString('vi-VN') : '')

function useMinuteClock() {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(timer)
  }, [])
  return now
}

/**
 * Chia se vi tri tu trinh duyet (du phong). Ung dung di dong la nguon GPS chinh; web chi gui khi
 * shipper chu dong bat (review PR 68, muc 4.12).
 */
function BrowserGpsPanel({ trackingCount }) {
  const [enabled, setEnabled] = useState(false)
  const gps = useShipperGps({ enabled: enabled && trackingCount > 0 })

  function requestPermission() {
    navigator.geolocation?.getCurrentPosition(
      () => setEnabled(true),
      () => {},
      { enableHighAccuracy: true, timeout: 20000 },
    )
  }

  return (
    <section className="shipper-gps" aria-label="Chia sẻ vị trí từ trình duyệt">
      <div>
        <strong>
          {!enabled
            ? 'Vị trí đang được gửi từ ứng dụng di động'
            : gps.sharing
              ? 'Trình duyệt này đang chia sẻ vị trí'
              : gps.watching
                ? 'Đang chờ máy chủ nhận vị trí…'
                : 'Đã dừng chia sẻ từ trình duyệt'}
        </strong>
        <span className="delivery-track__muted">
          {enabled
            ? `${GPS_PERMISSION_LABELS[gps.permission]}${gps.lastSentAt ? ` · gửi lúc ${formatDateTime(gps.lastSentAt)}` : ''}`
            : 'Dùng ứng dụng VMarket trên điện thoại để gửi vị trí. Chỉ bật ở đây khi không dùng được ứng dụng.'}
        </span>
        {gps.error && (
          <span className="shipper-gps__error" role="alert">
            {gps.error}
          </span>
        )}
        {!gps.error && gps.warning && enabled && (
          <span className="shipper-gps__warning" role="status">
            {gps.warning}
          </span>
        )}
      </div>
      <div className="shipper-gps__actions">
        {enabled && gps.permission === 'prompt' && (
          <button className="account-button" type="button" onClick={requestPermission}>
            Cấp quyền vị trí
          </button>
        )}
        <button
          className={`account-button${enabled ? ' account-button--secondary' : ''}`}
          type="button"
          aria-pressed={enabled}
          disabled={!trackingCount && !enabled}
          onClick={() => setEnabled((value) => !value)}
        >
          {enabled ? 'Dừng chia sẻ từ trình duyệt này' : 'Chia sẻ từ trình duyệt này'}
        </button>
      </div>
    </section>
  )
}

/**
 * Ca lam viec (review PR 68 lan 3, phan 2): chi shipper dang trong ca moi duoc tu dong giao don.
 * Vao ca khong bat GPS; ket thuc ca khi con chuyen chua lay hang thi hoi xac nhan.
 */
function ShiftBar({ reloadKey, onChanged }) {
  const [shift, setShift] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirming, setConfirming] = useState('')

  useEffect(() => {
    let current = true
    getShift()
      .then((data) => current && setShift(data))
      .catch((failure) => current && setError(getApiError(failure).message))
    return () => {
      current = false
    }
  }, [reloadKey])

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      setShift(await action())
      setConfirming('')
      onChanged()
    } catch (failure) {
      const apiError = getApiError(failure)
      if (apiError.code === 'SHIFT_END_CONFIRM_REQUIRED') setConfirming(apiError.message)
      else setError(apiError.message)
    } finally {
      setBusy(false)
    }
  }

  if (!shift && !error) return null
  const onShift = Boolean(shift?.onShift)
  return (
    <section className={`shipper-shift${onShift ? ' is-on' : ''}`} aria-label="Ca làm việc">
      <div>
        <strong>{onShift ? 'Bạn đang trong ca' : 'Bạn đang ngoài ca'}</strong>
        <span className="delivery-track__muted">
          {onShift
            ? `Đang nhận đơn mới${shift.shiftChangedAt ? ` · vào ca lúc ${formatDateTime(shift.shiftChangedAt)}` : ''}`
            : 'Bạn đang ngoài ca, sẽ không được giao đơn mới.'}
        </span>
        {error && (
          <span className="shipper-gps__error" role="alert">
            {error}
          </span>
        )}
      </div>
      {shift && (
        <button
          type="button"
          className={`account-button${onShift ? ' account-button--secondary' : ''}`}
          disabled={busy}
          onClick={() => run(onShift ? () => endShift() : startShift)}
        >
          {busy ? 'Đang cập nhật…' : onShift ? 'Kết thúc ca' : 'Bắt đầu ca'}
        </button>
      )}
      {confirming && (
        <Modal
          title="Kết thúc ca?"
          description={`${confirming}. Bạn vẫn muốn kết thúc ca?`}
          onClose={() => !busy && setConfirming('')}
        >
          <div className="account-actions">
            <button
              type="button"
              className="account-button account-button--danger"
              data-autofocus
              disabled={busy}
              onClick={() => run(() => endShift({ releaseUnpicked: true }))}
            >
              {busy ? 'Đang kết thúc…' : 'Kết thúc ca'}
            </button>
            <button
              type="button"
              className="account-button account-button--secondary"
              disabled={busy}
              onClick={() => setConfirming('')}
            >
              Ở lại ca
            </button>
          </div>
        </Modal>
      )}
    </section>
  )
}

function ContactLinks({ phone, location, address }) {
  const directions = directionsUrl({ location, address })
  return (
    <span className="delivery-contact">
      {phone && (
        <a className="delivery-contact__link" href={telUrl(phone)}>
          Gọi {phone}
        </a>
      )}
      {directions && (
        <a className="delivery-contact__link" href={directions} target="_blank" rel="noreferrer">
          Chỉ đường
        </a>
      )}
    </span>
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
          : `Thu hộ COD ${formatMoney(cod)} (miễn phí vận chuyển). Nhập đúng số tiền đã thu.`
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
            value={groupThousands(amount)}
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

/** Bao giao that bai bang 6 ly do chuan; "Khac" bat buoc ghi chu (review PR 68, muc 1.4). */
function FailDialog({ assignment, busy, error, onClose, onSubmit }) {
  const [code, setCode] = useState('')
  const [note, setNote] = useState('')
  const reason = failureReason(code)
  const lastAttempt = assignment.attempt >= (assignment.maxAttempts || 3)
  const returning = Boolean(reason?.returnImmediately) || lastAttempt
  const ready = reason && (!reason.noteRequired || note.trim())
  return (
    <Modal
      title="Báo giao thất bại"
      description={`Lần giao ${assignment.attempt || 1}/${assignment.maxAttempts || 3}. Người mua chỉ thấy lý do bạn chọn, không thấy ghi chú.`}
      onClose={onClose}
    >
      <form
        className="profile-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          onSubmit({ reasonCode: code, ...(note.trim() ? { reason: note.trim() } : {}) })
        }}
      >
        <AccountField
          as="select"
          label="Lý do"
          required
          data-autofocus
          value={code}
          onChange={(event) => setCode(event.target.value)}
        >
          <option value="">Chọn lý do</option>
          {FAILURE_REASONS.map((item) => (
            <option key={item.code} value={item.code}>
              {item.label}
            </option>
          ))}
        </AccountField>
        <AccountField
          as="textarea"
          label={reason?.noteRequired ? 'Ghi chú (bắt buộc)' : 'Ghi chú nội bộ (không bắt buộc)'}
          required={Boolean(reason?.noteRequired)}
          maxLength={255}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
        {reason && returning && (
          <p className="account-notice account-notice--warning" role="status">
            {reason.returnImmediately
              ? `"${reason.label}": chuyến sẽ chuyển sang hoàn hàng về shop ngay.`
              : 'Đây là lần giao cuối: chuyến sẽ chuyển sang hoàn hàng về shop.'}
          </p>
        )}
        {error && (
          <p className="account-notice account-notice--error" role="alert">
            {error}
          </p>
        )}
        <div className="account-actions">
          <button
            className="account-button account-button--danger"
            type="submit"
            disabled={busy || !ready}
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

/** Diem lay hang (ASSIGNED) hoac tra hang (RETURNING) theo tung shop. */
function ShopStops({ assignment, busy, onPickup, onReturn }) {
  const pickups = assignment.pickups || []
  const returning = assignment.status === 'RETURNING' || assignment.status === 'RETURN_DROPPED_OFF'
  if (!pickups.length || (assignment.status !== 'ASSIGNED' && !returning)) return null
  return (
    <div className="shipper-stops">
      <h3 className="delivery-track__title">
        {returning ? 'Trả hàng về các shop' : 'Điểm lấy hàng'}
      </h3>
      <ol className="shipper-stops__list">
        {pickups.map((shop) => {
          const done = returning ? shop.returned_at : shop.picked_up_at
          return (
            <li key={shop.shop_id} className={done ? 'is-done' : undefined}>
              <div>
                <strong>{shop.name || `Shop ${shop.shop_id}`}</strong>
                {shop.address && <span className="delivery-track__muted">{shop.address}</span>}
                <ContactLinks phone={shop.phone} location={shop.location} address={shop.address} />
              </div>
              {done ? (
                <span className="delivery-badge delivery-badge--delivered">
                  {returning ? 'Đã trả' : 'Đã lấy'} · {formatDateTime(done)}
                </span>
              ) : assignment.status === 'RETURN_DROPPED_OFF' ? null : (
                <button
                  type="button"
                  className="account-button"
                  disabled={busy}
                  onClick={() => (returning ? onReturn(shop) : onPickup(shop))}
                >
                  {returning ? 'Đã trả hàng cho shop này' : 'Đã lấy hàng ở shop này'}
                </button>
              )}
            </li>
          )
        })}
      </ol>
      {assignment.status === 'RETURN_DROPPED_OFF' && (
        <p className="delivery-track__muted">
          Đã trả hàng cho mọi shop. Chờ shop xác nhận đã nhận lại hàng.
        </p>
      )}
    </div>
  )
}

function AssignmentCard({ assignment, onChanged, onStale, now }) {
  const [dialog, setDialog] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [showMap, setShowMap] = useState(false)
  const recipient = assignment.recipient
  const returning = isReturnStatus(assignment.status)
  const cod = !returning && Number(assignment.codAmount) > 0
  const retryAt = assignment.earliestRetryAt ? new Date(assignment.earliestRetryAt) : null
  const retryLocked = Boolean(retryAt && retryAt.getTime() > now)

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
            Lần giao {assignment.attempt || 1}/{assignment.maxAttempts || 3} · nhận{' '}
            {formatDateTime(assignment.createdAt)}
            {assignment.completedAt && ` · kết thúc ${formatDateTime(assignment.completedAt)}`}
          </span>
        </div>
        <span className={`delivery-badge delivery-badge--${assignment.status.toLowerCase()}`}>
          {DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
        </span>
      </div>

      <dl className="shipper-card__info">
        {returning ? (
          <div>
            <dt>Nhiệm vụ</dt>
            <dd>Hoàn hàng về shop — không thu tiền người mua.</dd>
          </div>
        ) : (
          recipient && (
            <>
              <div>
                <dt>Người nhận</dt>
                <dd>
                  {recipient.name || '—'}
                  <ContactLinks
                    phone={recipient.phone}
                    location={recipient.location}
                    address={recipient.address}
                  />
                </dd>
              </div>
              <div>
                <dt>Địa chỉ giao</dt>
                <dd>{recipient.address || '—'}</dd>
              </div>
              {recipient.note && (
                <div>
                  <dt>Ghi chú</dt>
                  <dd>{recipient.note}</dd>
                </div>
              )}
            </>
          )
        )}
        <div>
          <dt>Khu giao hàng</dt>
          <dd>
            {[assignment.ward, assignment.zone && `khu ${assignment.zone}`]
              .filter(Boolean)
              .join(', ') || '—'}
          </dd>
        </div>
        {!returning && (
          <div>
            <dt>Thu hộ</dt>
            <dd>
              {cod ? `COD ${formatMoney(Number(assignment.codAmount))}` : 'Đã thanh toán trước'}
            </dd>
          </div>
        )}
        {assignment.itemCount > 0 && (
          <div>
            <dt>Hàng hoá</dt>
            <dd>{assignment.itemCount} sản phẩm</dd>
          </div>
        )}
      </dl>

      {(assignment.failures || []).length > 0 && (
        <div className="shipper-failures">
          <h3 className="delivery-track__title">Các lần giao chưa thành công</h3>
          <ul>
            {assignment.failures.map((item) => (
              <li key={`${item.attempt}-${item.occurred_at}`}>
                Lần {item.attempt}: {item.public_reason || 'Không rõ lý do'} ·{' '}
                {formatDateTime(item.occurred_at)}
              </li>
            ))}
          </ul>
        </div>
      )}

      <ShopStops
        assignment={assignment}
        busy={busy}
        onPickup={(shop) =>
          run(() => confirmPickup(assignment.id, shop.shop_id, assignment.version))
        }
        onReturn={(shop) =>
          run(() => confirmReturn(assignment.id, shop.shop_id, assignment.version))
        }
      />

      {error && !dialog && (
        <p className="account-notice account-notice--error" role="alert">
          {error}
        </p>
      )}

      <div className="account-actions shipper-card__actions">
        {shipperActions(assignment).map((action) => {
          const locked = action.kind === 'retry' && retryLocked
          return (
            <button
              key={action.label}
              type="button"
              className={`account-button${action.danger ? ' account-button--danger' : ''}`}
              disabled={busy || locked}
              onClick={() => onAction(action)}
            >
              {locked ? `Giao lại từ ${formatDateTime(retryAt)}` : action.label}
            </button>
          )
        })}
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

      {showMap && (
        <DeliveryTrackingPanel
          orderId={assignment.orderId}
          audience="shipper"
          showHistory={false}
        />
      )}

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

/**
 * Phan he shipper tren web (PBL6-33) - ban du phong; ung dung di dong la ban chinh.
 * Tab "Dang xu ly" tai MOI chuyen con viec (scope=active); "Da ket thuc" phan trang rieng.
 */
export default function ShipperPage() {
  const [tab, setTab] = useState('active')
  const [active, setActive] = useState({ status: 'loading', items: [], error: '' })
  const [done, setDone] = useState({ status: 'idle', items: [], error: '', hasMore: false })
  const [pages, setPages] = useState(1)
  const [reloadKey, setReloadKey] = useState(0)
  const reload = useCallback(() => setReloadKey((key) => key + 1), [])
  const now = useMinuteClock()

  useEffect(() => {
    let current = true
    listMyAssignments({ scope: 'active' })
      .then((items) => current && setActive({ status: 'ready', items: items || [], error: '' }))
      .catch(
        (failure) =>
          current &&
          setActive((previous) => ({
            ...previous,
            status: previous.items.length ? 'ready' : 'error',
            error: getApiError(failure).message,
          })),
      )
    return () => {
      current = false
    }
  }, [reloadKey])

  useEffect(() => {
    if (tab !== 'done') return undefined
    let current = true
    ;(async () => {
      try {
        const items = []
        let last = []
        for (let page = 0; page < pages; page += 1) {
          last = (await listMyAssignments({ scope: 'finished', page, size: PAGE_SIZE })) || []
          items.push(...last)
          if (last.length < PAGE_SIZE) break
        }
        if (current)
          setDone({ status: 'ready', items, error: '', hasMore: last.length === PAGE_SIZE })
      } catch (failure) {
        if (current)
          setDone((previous) => ({
            ...previous,
            status: previous.items.length ? 'ready' : 'error',
            error: getApiError(failure).message,
          }))
      }
    })()
    return () => {
      current = false
    }
  }, [tab, pages, reloadKey])

  useEffect(() => {
    const timer = setInterval(reload, AUTO_REFRESH_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible') reload()
    }
    window.addEventListener('focus', reload)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', reload)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [reload])

  function onChanged(updated) {
    const replace = (previous) => ({
      ...previous,
      items: previous.items.map((item) => (item.id === updated.id ? updated : item)),
    })
    setActive(replace)
    setDone(replace)
  }

  const state = tab === 'active' ? active : done
  const visible =
    tab === 'active' ? active.items.filter((item) => !isTerminalStatus(item.status)) : done.items
  const trackingCount = active.items.filter((item) =>
    ['PICKED_UP', 'IN_TRANSIT', 'RETURNING'].includes(item.status),
  ).length

  return (
    <main className="account-page shipper-page">
      <div className="shipper-page__header">
        <div>
          <span className="account-eyebrow">Khu vực giao hàng</span>
          <h1>Đơn được phân công</h1>
          <p className="account-subtitle">
            Lấy hàng ở từng shop, cập nhật trạng thái giao và trả hàng hoàn. Ứng dụng VMarket trên
            điện thoại là kênh chính; trang web này để dự phòng.
          </p>
        </div>
        <button className="account-button account-button--secondary" type="button" onClick={reload}>
          Làm mới
        </button>
      </div>

      <ShiftBar reloadKey={reloadKey} onChanged={reload} />

      <BrowserGpsPanel trackingCount={trackingCount} />

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
            {value === 'active' && active.status === 'ready' && ` (${active.items.length})`}
          </button>
        ))}
      </div>

      {(state.status === 'loading' || state.status === 'idle') && (
        <p className="delivery-track__muted">Đang tải…</p>
      )}
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
              now={now}
            />
          ))}
        </ul>
      )}
      {tab === 'done' && done.status === 'ready' && done.hasMore && (
        <button
          className="account-button account-button--secondary shipper-page__more"
          type="button"
          onClick={() => setPages((value) => value + 1)}
        >
          Tải thêm chuyến cũ
        </button>
      )}
    </main>
  )
}
