import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import useDeliveryTracking from '../hooks/useDeliveryTracking'
import { useReloadOnNotification } from '../notifications/events'
import { getApiError } from '../services/api/apiClient'
import {
  getDelivery,
  getRoute,
  isTerminalStatus,
  isTrackingStatus,
  serverNow,
} from '../services/api/deliveryApi'
import {
  BUYER_MAP_STATUSES,
  deliveryStatusLabel,
  publicHistory,
  telUrl,
} from '../utils/deliveryStatus'
import { formatDateTime } from '../utils/formatDate'
import './delivery.css'

// Leaflet nang (~150KB) va can DOM that: chi tai khi thuc su mo ban do.
const DeliveryMap = lazy(() => import('./DeliveryMap'))

// Chua duoc cap ve theo doi (chuyen chua lay hang): tai lai chi tiet dinh ky de tu chuyen sang ban
// do khi shipper lay hang (review PR 68 lan 3, muc 1.2).
const WAITING_REFRESH_MS = 10_000

const CONNECTION_LABELS = {
  idle: 'Chưa kết nối',
  connecting: 'Đang kết nối…',
  live: 'Trực tiếp',
  reconnecting: 'Mất kết nối, đang nối lại…',
  ended: 'Chuyến giao đã kết thúc',
  error: 'Không theo dõi được',
}

const toPoint = (row) => ({
  latitude: Number(row.latitude),
  longitude: Number(row.longitude),
  recordedAt: row.recorded_at ?? row.recordedAt,
})

function useServerNow(active) {
  const [now, setNow] = useState(serverNow)
  useEffect(() => {
    if (!active) return undefined
    const timer = setInterval(() => setNow(serverNow()), 1000)
    return () => clearInterval(timer)
  }, [active])
  return now
}

/**
 * Theo doi chuyen giao cua mot don (PBL6-33): trang thai, ban do vi tri shipper qua WebSocket,
 * lich su trang thai. Quyen xem theo NFR-SEC-07 (review PR 68, muc 1.6):
 *  - buyer: chi thay ban do khi chuyen o PICKED_UP / IN_TRANSIT; khong thay ly do noi bo.
 *  - admin / shipper: thay vi tri bat cu khi nao chuyen co GPS.
 */
export default function DeliveryTrackingPanel({
  orderId,
  audience = 'buyer',
  showHistory = true,
  orderStatus,
}) {
  const [detail, setDetail] = useState({ status: 'loading', data: null, error: '' })
  const [route, setRoute] = useState([])
  const [reloadKey, setReloadKey] = useState(0)
  const buyer = audience === 'buyer'

  const assignment = detail.data?.assignment
  const loadedStatus = assignment?.status

  useEffect(() => {
    let current = true
    setDetail((previous) => ({ ...previous, status: 'loading' }))
    getDelivery(orderId)
      .then(async (data) => {
        const status = data?.assignment?.status
        const allowed = buyer ? BUYER_MAP_STATUSES.includes(status) : Boolean(status)
        const points = allowed ? await getRoute(orderId).catch(() => []) : []
        if (!current) return
        setDetail({ status: 'ready', data, error: '' })
        setRoute((points || []).map(toPoint))
      })
      .catch((failure) => {
        if (!current) return
        // Lan tai lai dinh ky bi loi thi giu du lieu cu, khong lam mat khung theo doi.
        setDetail((previous) =>
          previous.data && failure?.response?.status !== 404
            ? { ...previous, status: 'ready' }
            : {
                status: failure?.response?.status === 404 ? 'missing' : 'error',
                data: null,
                error: getApiError(failure).message,
              },
        )
      })
    return () => {
      current = false
    }
  }, [orderId, reloadKey, buyer, orderStatus])

  const trackable =
    Boolean(assignment) &&
    !isTerminalStatus(loadedStatus) &&
    (buyer ? BUYER_MAP_STATUSES.includes(loadedStatus) : true)
  const live = useDeliveryTracking(orderId, { enabled: trackable })

  // Trong luc cho (chua mo WebSocket): hoi lai dinh ky va khi co thong bao moi / quay lai tab.
  const waiting = detail.status !== 'missing' && !trackable && !isTerminalStatus(loadedStatus)
  useReloadOnNotification(() => waiting && setReloadKey((k) => k + 1))
  useEffect(() => {
    if (!waiting) return undefined
    const timer = setInterval(() => {
      if (document.visibilityState !== 'hidden') setReloadKey((k) => k + 1)
    }, WAITING_REFRESH_MS)
    return () => clearInterval(timer)
  }, [waiting])
  const update = live.update

  // Diem GPS moi tu WebSocket noi vao duong di da tai.
  useEffect(() => {
    const location = update?.location
    if (!location || !Number.isFinite(location.latitude)) return
    setRoute((previous) => {
      const last = previous[previous.length - 1]
      if (last && last.recordedAt === location.recordedAt) return previous
      return [...previous, toPoint(location)]
    })
  }, [update])

  // Trang thai doi qua socket thi tai lai chi tiet (lich su, quyen xem ban do).
  const liveStatus = update?.status
  useEffect(() => {
    if (liveStatus && loadedStatus && liveStatus !== loadedStatus) setReloadKey((k) => k + 1)
  }, [liveStatus, loadedStatus])

  const status = liveStatus || loadedStatus
  // Admin / shipper: ban do khi chuyen dang co GPS hoac da co hanh trinh.
  const showMap = buyer
    ? BUYER_MAP_STATUSES.includes(status)
    : isTrackingStatus(status) || route.length > 0
  const position = useMemo(() => {
    if (update?.location?.latitude != null) return toPoint(update.location)
    // Nguoi mua: API chi tra vi tri khi PICKED_UP / IN_TRANSIT (assignment.location).
    if (assignment?.location?.latitude != null) return toPoint(assignment.location)
    if (assignment?.latitude != null)
      return {
        latitude: assignment.latitude,
        longitude: assignment.longitude,
        recordedAt: assignment.locationAt,
      }
    return route[route.length - 1] || null
  }, [update, assignment, route])

  const now = useServerNow(showMap && live.connection === 'live')
  const age = position?.recordedAt
    ? Math.max(0, Math.round((now - new Date(position.recordedAt).getTime()) / 1000))
    : null

  if (detail.status === 'loading' && !detail.data)
    return (
      <section className="delivery-track" aria-busy="true">
        <p className="delivery-track__muted">Đang tải thông tin giao hàng…</p>
      </section>
    )
  if (detail.status === 'missing') return null
  if (detail.status === 'error')
    return (
      <section className="delivery-track">
        <p role="alert">{detail.error}</p>
        <button className="account-button" type="button" onClick={() => setReloadKey((k) => k + 1)}>
          Thử lại
        </button>
      </section>
    )

  const history = buyer ? publicHistory(detail.data?.history) : detail.data?.history || []
  // Diem giao (toa do nguoi mua chon khi luu dia chi): buyer / admin / shipper.
  const order = detail.data?.order
  const destination =
    assignment?.deliveryPoint ||
    assignment?.recipient?.location ||
    (order && Number.isFinite(order.latitude)
      ? { latitude: order.latitude, longitude: order.longitude }
      : null)
  const shipper = buyer ? assignment?.shipper : null
  const retryAt = buyer && status === 'FAILED' ? assignment?.earliestRetryAt : null

  return (
    <section className="delivery-track" aria-label="Theo dõi giao hàng">
      <div className="delivery-track__head">
        <div>
          <span className="delivery-track__eyebrow">Vận chuyển</span>
          <strong className="delivery-track__status">
            {deliveryStatusLabel(status, buyer ? 'buyer' : 'staff')}
          </strong>
        </div>
        {trackable && showMap && (
          <span
            className={`delivery-live delivery-live--${live.connection}`}
            role="status"
            aria-live="polite"
          >
            {CONNECTION_LABELS[live.connection]}
          </span>
        )}
      </div>

      {shipper && (
        <p className="delivery-track__shipper">
          Shipper: <strong>{shipper.name}</strong>
          {shipper.phone && (
            <>
              {' · '}
              <a href={telUrl(shipper.phone)}>{shipper.phone}</a>
            </>
          )}
        </p>
      )}
      {retryAt && (
        <p className="delivery-track__muted">Dự kiến giao lại từ {formatDateTime(retryAt)}.</p>
      )}

      {showMap && (
        <>
          <Suspense fallback={<div className="delivery-map delivery-map--placeholder" />}>
            <DeliveryMap position={position} route={route} destination={destination} />
          </Suspense>
          <p className="delivery-track__muted">
            {position
              ? `Vị trí cập nhật ${age === null ? '' : age < 5 ? 'vừa xong' : `${age} giây trước`} · ${formatDateTime(position.recordedAt)}`
              : 'Shipper chưa chia sẻ vị trí.'}
          </p>
        </>
      )}
      {!showMap && buyer && !isTerminalStatus(status) && (
        <p className="delivery-track__muted">
          Bản đồ hiển thị khi shipper đã lấy hàng và đang giao tới bạn.
        </p>
      )}

      {showHistory && history.length > 0 && (
        <div>
          <h3 className="delivery-track__title">Lịch sử trạng thái</h3>
          <ol className="delivery-timeline">
            {history.map((entry, index) => (
              <li key={`${entry.status}-${entry.occurred_at}-${index}`}>
                <strong>{deliveryStatusLabel(entry.status, buyer ? 'buyer' : 'staff')}</strong>
                <span>{formatDateTime(entry.occurred_at)}</span>
                {entry.public_reason && (
                  <span className="delivery-track__muted">{entry.public_reason}</span>
                )}
                {!buyer && entry.reason && (
                  <span className="delivery-track__muted">Ghi chú nội bộ: {entry.reason}</span>
                )}
              </li>
            ))}
          </ol>
        </div>
      )}
    </section>
  )
}
