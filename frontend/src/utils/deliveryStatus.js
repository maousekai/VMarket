import { DA_NANG_PROVINCE } from '../data/daNangAddress'
import RULES from '../generated/delivery-rules.json'

// Nhan trang thai, ly do that bai va khu giao hang doc tu ban sao sinh ra cua
// shared/delivery/delivery-rules.json - MOT nguon cho backend, web va mobile (review PR 68/69).
// Sua file goc roi chay: node scripts/sync-delivery-rules.mjs

/** Nhan cho shipper / admin / seller. */
export const DELIVERY_STATUS_LABELS = Object.freeze({ ...RULES.statusLabels.staff })

/** Nhan cho nguoi mua: khong lo chi tiet dieu phoi noi bo. */
export const BUYER_DELIVERY_STATUS_LABELS = Object.freeze({ ...RULES.statusLabels.buyer })

export const deliveryStatusLabel = (status, audience = 'staff') =>
  (audience === 'buyer' ? BUYER_DELIVERY_STATUS_LABELS : DELIVERY_STATUS_LABELS)[status] || status

/** 6 ly do giao that bai chuan: {code, label, returnImmediately, shipperFault, noteRequired}. */
export const FAILURE_REASONS = Object.freeze(RULES.failureReasons.map((r) => Object.freeze(r)))
export const failureReason = (code) => FAILURE_REASONS.find((reason) => reason.code === code)

export const RETRY_HOURS = RULES.retry.minHoursBetweenAttempts
export const MAX_ATTEMPTS = RULES.retry.maxAttempts
export const RETURN_AUTO_CLOSE_DAYS = RULES.returnAutoCloseDays

/** Nguoi mua chi xem vi tri shipper khi hang dang tren duong toi (NFR-SEC-07). */
export const BUYER_MAP_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT'])

/** Chuyen dang / da hoan hang: khong giao cho nguoi mua, khong thu COD. */
export const RETURN_STATUSES = Object.freeze(['RETURNING', 'RETURN_DROPPED_OFF', 'RETURNED'])
export const isReturnStatus = (status) => RETURN_STATUSES.includes(status)

/** Hang da roi shop: khong doi shipper duoc, chi "Chuyen hoan hang". */
export const PICKED_UP_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT', 'FAILED'])

/** Chuyen can admin xu ly: tu dong phan cong that bai. */
export const NEEDS_DISPATCH_STATUS = 'WAITING_ASSIGNMENT'

/**
 * Lich su trang thai hien cho nguoi mua: chi trang thai, thoi gian va ly do cong khai; gop cac
 * dong lien tiep trung trang thai (vd doi shipper sinh them mot dong "Dang giao").
 */
export function publicHistory(history = []) {
  const rows = []
  for (const entry of history) {
    if (rows.length && rows[rows.length - 1].status === entry.status) continue
    const row = { status: entry.status, occurred_at: entry.occurred_at }
    if (entry.public_reason) row.public_reason = entry.public_reason
    rows.push(row)
  }
  return rows
}

// ---- Khu giao hang ----

/**
 * Khoa so khop khu vuc, giong het AreaNames.normalize cua delivery-service: bo dau, chu thuong,
 * 'd' thay 'đ', gom khoang trang, bo tien to hanh chinh (thanh pho, quan, huyen, tp, q...).
 */
export function normalizeArea(value) {
  if (!value) return ''
  const key = String(value)
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/đ/g, 'd')
    .replace(/[\p{Z}\s]+/gu, ' ')
    .trim()
  return key.replace(/^(thanh pho|thi xa|tinh|quan|huyen|tp|tx|q|h)(?:\s*\.\s*|\s+)/, '').trim()
}

/** Tinh/thanh duoc phep chon khi tao shipper (VMarket hoat dong tai mot thanh pho). */
export const SHIPPER_PROVINCES = Object.freeze([DA_NANG_PROVINCE])
/** 7 khu giao hang (gom tu phuong/xa sau khi bo cap quan tu 01/07/2025). */
export const DELIVERY_ZONES = Object.freeze(RULES.zones.list.map((zone) => zone.name))
/** @deprecated giu ten cu cho code dang goi; la danh sach khu giao hang. */
export const SHIPPER_DISTRICTS = DELIVERY_ZONES

const WARD_TO_ZONE = new Map(
  RULES.zones.list.flatMap((zone) =>
    zone.wards.map((ward) => [normalizeArea(ward), normalizeArea(zone.name)]),
  ),
)

/** Khu giao hang (khoa chuan hoa) cua mot phuong/xa; phuong la thi ''. */
export const zoneOfWard = (ward) => WARD_TO_ZONE.get(normalizeArea(ward)) || ''

const DISPLAY_NAMES = new Map(
  [RULES.zones.province, ...DELIVERY_ZONES].map((name) => [normalizeArea(name), name]),
)

/** Ten co dau tu khoa da chuan hoa ('hai chau' -> 'Hải Châu'); khong biet thi giu nguyen. */
export const displayAreaName = (value) =>
  value ? DISPLAY_NAMES.get(normalizeArea(value)) || value : ''

/** "Phuong, Khu ..., Tinh" - hien ca phuong lan khu giao hang. */
export const displayArea = (row) =>
  [
    row?.ward,
    row?.district ? `khu ${displayAreaName(row.district)}` : '',
    displayAreaName(row?.province),
  ]
    .filter(Boolean)
    .join(', ') || '—'

/** Giong DeliveryService.isAreaMismatch: khac tinh, hoac ca hai deu co khu va khac khu. */
export function isAreaMismatch(shipper, assignment) {
  if (normalizeArea(shipper?.province) !== normalizeArea(assignment?.province)) return true
  const shipperZone = normalizeArea(shipper?.district)
  const orderZone = normalizeArea(assignment?.district)
  if (!shipperZone || !orderZone) return false
  return shipperZone !== orderZone
}

export const SHIPPER_ACCOUNT_LABELS = Object.freeze({
  PENDING_ACTIVATION: 'Chờ kích hoạt',
  ACTIVE: 'Hoạt động',
  LOCKED: 'Đã khoá',
  UNKNOWN: 'Chưa rõ',
})

/** Trang thai tai khoan shipper; API cu chua co account_status thi suy tu co khoa cuc bo. */
export const shipperAccountStatus = (shipper) =>
  shipper?.account_status || (shipper?.active ? 'UNKNOWN' : 'LOCKED')

// ---- Lien he / chi duong ----

/** Link Google Maps chi duong toi toa do (uu tien) hoac dia chi. */
export function directionsUrl({ location, address } = {}) {
  const destination =
    location && Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
      ? `${location.latitude},${location.longitude}`
      : address
  if (!destination) return ''
  return `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(destination)}`
}

export const telUrl = (phone) => (phone ? `tel:${String(phone).replace(/[^\d+]/g, '')}` : '')
