import type { Assignment, DeliveryStatus } from '@/api/delivery'
import type { Tone } from '@/theme'

/** Initial delivery + 2 redeliveries (delivery-service limit, CHECK attempt BETWEEN 1 AND 3). */
export const MAX_ATTEMPTS = 3
export const MAX_REDELIVERIES = MAX_ATTEMPTS - 1

export const STATUS_LABEL: Partial<Record<DeliveryStatus, string>> = {
  ASSIGNED: 'Chờ lấy hàng',
  PICKED_UP: 'Đã lấy hàng',
  IN_TRANSIT: 'Đang giao',
  FAILED: 'Giao thất bại',
  RETURNING: 'Đang hoàn về',
  RETURNED: 'Đã hoàn về',
  DELIVERED: 'Đã giao',
}

export const STATUS_TONE: Partial<Record<DeliveryStatus, Tone>> = {
  ASSIGNED: 'warning',
  PICKED_UP: 'info',
  IN_TRANSIT: 'transit',
  FAILED: 'danger',
  RETURNING: 'warning',
  RETURNED: 'neutral',
  DELIVERED: 'success',
}

/** Statuses in which the shipper still has work to do on the assignment. */
const ACTIVE: ReadonlySet<DeliveryStatus> = new Set([
  'ASSIGNED',
  'PICKED_UP',
  'IN_TRANSIT',
  'FAILED',
  'RETURNING',
])

/** Statuses in which delivery-service accepts GPS pings (DeliveryStatus.tracking()). */
const TRACKING: ReadonlySet<DeliveryStatus> = new Set(['PICKED_UP', 'IN_TRANSIT', 'RETURNING'])

export const isActive = (a: Assignment) => ACTIVE.has(a.status)
export const isTracking = (a: Assignment) => TRACKING.has(a.status)
export const isFinished = (a: Assignment) => a.status === 'DELIVERED' || a.status === 'RETURNED'

/** "Đang giao lại · 1/2": redelivery number of the current attempt. */
export const redeliveryNumber = (a: Pick<Assignment, 'attempt'>) => Math.max(0, a.attempt - 1)

const RANK: Partial<Record<DeliveryStatus, number>> = {
  IN_TRANSIT: 0,
  PICKED_UP: 0,
  RETURNING: 1,
  FAILED: 1,
  ASSIGNED: 2,
}

/** In-progress work first, then waiting pickup; each group oldest first. */
export function sortActive(list: Assignment[]): Assignment[] {
  return list
    .filter(isActive)
    .sort(
      (a, b) =>
        (RANK[a.status] ?? 9) - (RANK[b.status] ?? 9) || a.createdAt.localeCompare(b.createdAt),
    )
}
