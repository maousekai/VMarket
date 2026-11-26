import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import useDeliveryTracking from '../hooks/useDeliveryTracking'
import { getApiError } from '../services/api/apiClient'
import {
  DELIVERY_STATUS_LABELS,
  getDelivery,
  getRoute,
  isTerminalStatus,
  isTrackingStatus,
} from '../services/api/deliveryApi'
import { formatDateTime } from '../utils/formatDate'
import './delivery.css'

// Leaflet nang (~150KB) va can DOM that: chi tai khi thuc su mo ban do.
const DeliveryMap = lazy(() => import('./DeliveryMap'))

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

function secondsAgo(timestamp, now) {
  if (!timestamp) return null
  return Math.max(0, Math.round((now - new Date(timestamp).getTime()) / 1000))
}

function useNow(active) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return undefined
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active])
  return now
}

/**
 * Theo doi chuyen giao cua mot don (PBL6-33): trang thai, ban do vi tri shipper cap nhat qua
 * WebSocket, lich su hanh trinh (route_logs) va lich su trang thai. Dung cho nguoi mua (trang
 * chi tiet don) va admin (dieu phoi shipper).
 */
export default function DeliveryTrackingPanel({ orderId, showHistory = true }) {
  const [detail, setDetail] = useState({ status: 'loading', data: null, error: '' })
  const [route, setRoute] = useState([])
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    setDetail((previous) => ({ ...previous, status: 'loading' }))
    Promise.all([getDelivery(orderId), getRoute(orderId).catch(() => [])])
      .then(([data, points]) => {
        if (!current) return
        setDetail({ status: 'ready', data, error: '' })
        setRoute((points || []).map(toPoint))
      })
      .catch((failure) => {
        if (!current) return
        const error = getApiError(failure)
        setDetail({
          status: failure?.response?.status === 404 ? 'missing' : 'error',
          data: null,
          error: error.message,
        })
      })
    return () => {
      current = false
    }
  }, [orderId, reloadKey])

  const assignment = detail.data?.assignment
  const trackable = Boolean(assignment) && !isTerminalStatus(assignment.status)
  const live = useDeliveryTracking(orderId, { enabled: trackable })
  const update = live.update

  // Moi diem GPS moi tu WebSocket noi vao duong di da tai.
  useEffect(() => {
    const location = update?.location
    if (!location || !Number.isFinite(location.latitude)) return
    setRoute((previous) => {
      const last = previous[previous.length - 1]
      if (last && last.recordedAt === location.recordedAt) return previous
      return [...previous, toPoint(location)]
    })
  }, [update])

  // Trang thai doi qua socket thi tai lai lich su trang thai.
  const liveStatus = update?.status
  useEffect(() => {
    if (liveStatus && assignment && liveStatus !== assignment.status) setReloadKey((k) => k + 1)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveStatus])

  const status = liveStatus || assignment?.status
  const position = useMemo(() => {
    if (update?.location?.latitude != null) return toPoint(update.location)
    if (assignment?.latitude != null)
      return {
        latitude: assignment.latitude,
        longitude: assignment.longitude,
        recordedAt: assignment.locationAt,
      }
    return route[route.length - 1] || null
  }, [update, assignment, route])

  const now = useNow(live.connection === 'live')
  const age = secondsAgo(position?.recordedAt, now)

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

  const history = detail.data?.history || []
  const showMap = isTrackingStatus(status) || route.length > 0

  return (
    <section className="delivery-track" aria-label="Theo dõi giao hàng">
      <div className="delivery-track__head">
        <div>
          <span className="delivery-track__eyebrow">Vận chuyển</span>
          <strong className="delivery-track__status">
            {DELIVERY_STATUS_LABELS[status] || status}
          </strong>
        </div>
        {trackable && (
          <span
            className={`delivery-live delivery-live--${live.connection}`}
            role="status"
            aria-live="polite"
          >
            {CONNECTION_LABELS[live.connection]}
          </span>
        )}
      </div>

      {showMap ? (
        <>
          <Suspense fallback={<div className="delivery-map delivery-map--placeholder" />}>
            <DeliveryMap position={position} route={route} />
          </Suspense>
          <p className="delivery-track__muted">
            {position
              ? `Vị trí cập nhật ${age === null ? '' : age < 5 ? 'vừa xong' : `${age} giây trước`} · ${formatDateTime(position.recordedAt)}`
              : 'Shipper chưa chia sẻ vị trí.'}
          </p>
        </>
      ) : (
        <p className="delivery-track__muted">
          Bản đồ hiển thị khi shipper đã lấy hàng và bắt đầu giao.
        </p>
      )}

      {showHistory && (
        <div className="delivery-track__columns">
          {history.length > 0 && (
            <div>
              <h3 className="delivery-track__title">Lịch sử trạng thái</h3>
              <ol className="delivery-timeline">
                {history.map((entry, index) => (
                  <li key={`${entry.status}-${entry.occurred_at}-${index}`}>
                    <strong>{DELIVERY_STATUS_LABELS[entry.status] || entry.status}</strong>
                    <span>{formatDateTime(entry.occurred_at)}</span>
                    {entry.reason && <span className="delivery-track__muted">{entry.reason}</span>}
                  </li>
                ))}
              </ol>
            </div>
          )}
          {route.length > 0 && (
            <div>
              <h3 className="delivery-track__title">Lịch sử hành trình ({route.length} điểm)</h3>
              <ol className="delivery-route" reversed>
                {[...route]
                  .reverse()
                  .slice(0, 20)
                  .map((point, index) => (
                    <li key={`${point.recordedAt}-${index}`}>
                      <span>{formatDateTime(point.recordedAt)}</span>
                      <span className="delivery-track__coords">
                        {point.latitude.toFixed(5)}, {point.longitude.toFixed(5)}
                      </span>
                    </li>
                  ))}
              </ol>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
