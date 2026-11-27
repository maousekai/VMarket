import { ACTIVE_CITY } from '../data/city'
import { DA_NANG_PROVINCE } from '../data/daNangAddress'

// Bang nhan trang thai chuyen giao DUNG CHUNG (review PR 68, muc 1.3). Mobile dung cung bang nay.

/** Nhan cho shipper / admin / seller. */
export const DELIVERY_STATUS_LABELS = Object.freeze({
  WAITING_HANDOFF: 'Chờ shop bàn giao',
  WAITING_ASSIGNMENT: 'Chờ phân công',
  ASSIGNED: 'Đã phân công shipper',
  PICKED_UP: 'Đã lấy hàng',
  IN_TRANSIT: 'Đang giao',
  FAILED: 'Giao thất bại',
  DELIVERED: 'Đã giao',
  RETURNING: 'Đang hoàn hàng',
  RETURN_DROPPED_OFF: 'Chờ shop xác nhận nhận lại',
  RETURNED: 'Đã hoàn hàng',
})

/** Nhan cho nguoi mua: khong lo chi tiet dieu phoi noi bo. */
export const BUYER_DELIVERY_STATUS_LABELS = Object.freeze({
  WAITING_HANDOFF: 'Đang chuẩn bị hàng',
  WAITING_ASSIGNMENT: 'Đang chuẩn bị hàng',
  ASSIGNED: 'Đang chuẩn bị hàng',
  PICKED_UP: 'Đang giao',
  IN_TRANSIT: 'Đang giao',
  FAILED: 'Giao chưa thành công, sẽ giao lại',
  DELIVERED: 'Đã giao',
  RETURNING: 'Giao không thành công, đang hoàn về shop',
  RETURN_DROPPED_OFF: 'Giao không thành công, đang hoàn về shop',
  RETURNED: 'Đã hoàn trả cho shop',
})

export const deliveryStatusLabel = (status, audience = 'staff') =>
  (audience === 'buyer' ? BUYER_DELIVERY_STATUS_LABELS : DELIVERY_STATUS_LABELS)[status] || status

/** Nguoi mua chi xem vi tri shipper khi hang dang tren duong toi (NFR-SEC-07). */
export const BUYER_MAP_STATUSES = Object.freeze(['PICKED_UP', 'IN_TRANSIT'])

/** Chuyen can admin xu ly: tu dong phan cong that bai. */
export const NEEDS_DISPATCH_STATUS = 'WAITING_ASSIGNMENT'

/**
 * Lich su trang thai hien cho nguoi mua: khong co ly do noi bo, va gop cac dong lien tiep trung
 * trang thai (vd doi shipper sinh them mot dong "Dang giao").
 */
export function publicHistory(history = []) {
  const rows = []
  for (const entry of history) {
    if (rows.length && rows[rows.length - 1].status === entry.status) continue
    rows.push({ status: entry.status, occurred_at: entry.occurred_at })
  }
  return rows
}

// ---- Khu vuc ----

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
/** Quan/huyen cu cua Da Nang - don vi phan cong shipper. */
export const SHIPPER_DISTRICTS = Object.freeze([...ACTIVE_CITY.districts, 'Hòa Vang'])

const DISPLAY_NAMES = new Map(
  [ACTIVE_CITY.name, ...SHIPPER_DISTRICTS].map((name) => [normalizeArea(name), name]),
)

/** Ten co dau tu khoa da chuan hoa ('hai chau' -> 'Hải Châu'); khong biet thi giu nguyen. */
export const displayAreaName = (value) =>
  value ? DISPLAY_NAMES.get(normalizeArea(value)) || value : ''

export const displayArea = (row) =>
  [displayAreaName(row?.district), displayAreaName(row?.province)].filter(Boolean).join(', ') || '—'

/** Giong DeliveryService.isAreaMismatch: khac tinh, hoac ca hai deu co quan va khac quan. */
export function isAreaMismatch(shipper, assignment) {
  if (normalizeArea(shipper?.province) !== normalizeArea(assignment?.province)) return true
  const shipperDistrict = normalizeArea(shipper?.district)
  const orderDistrict = normalizeArea(assignment?.district)
  if (!shipperDistrict || !orderDistrict) return false
  return shipperDistrict !== orderDistrict
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
