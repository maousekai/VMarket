import { api } from '@/api/client'

export type DeliveryStatus =
  | 'WAITING_HANDOFF'
  | 'WAITING_ASSIGNMENT'
  | 'ASSIGNED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'FAILED'
  | 'RETURNING'
  | 'RETURNED'
  | 'DELIVERED'

/** delivery-service `Assignment` (GET /api/delivery/shipper/assignments). */
export interface Assignment {
  id: string
  orderId: string
  buyerId: string
  shipperId: string | null
  paymentMethod: 'COD' | 'PAYOS' | string
  province: string
  district: string | null
  /** JSON string of the Order-service snapshot taken at hand-off. */
  orderSnapshot: string
  status: DeliveryStatus
  /** 1 = first delivery; each redelivery adds one (max 3). */
  attempt: number
  codAmount: number
  version: number
  latitude: number | null
  longitude: number | null
  locationAt: string | null
  createdAt: string
  updatedAt: string
}

/** One `delivery_status_history` row as returned by GET /orders/{orderId}. */
export interface HistoryRow {
  status: DeliveryStatus
  reason?: string | null
  attempt: number
  occurred_at: string
}

/** One shop hand-off; `pickup_snapshot` is a JSON string of the shop profile. */
export interface PickupRow {
  shop_id: string
  pickup_snapshot: string
}

export interface DeliveryDetail {
  assignment: Assignment
  /** Order-service snapshot (parsed JSON object). */
  order: Record<string, unknown>
  pickups: PickupRow[]
  history: HistoryRow[]
}

export interface StatusUpdate {
  status: DeliveryStatus
  /** Required for FAILED (<= 255 chars). */
  reason?: string
  /** Required for DELIVERED and must equal `codAmount` (0 for prepaid). */
  codCollected?: number
  expectedVersion: number
}

export const ASSIGNMENTS_PAGE_SIZE = 100

export async function fetchAssignments(page: number): Promise<Assignment[]> {
  const res = await api.get<Assignment[]>('/api/delivery/shipper/assignments', {
    params: { page, size: ASSIGNMENTS_PAGE_SIZE },
  })
  return res.data
}

export async function fetchDeliveryDetail(orderId: string): Promise<DeliveryDetail> {
  const res = await api.get<DeliveryDetail>(`/api/delivery/orders/${orderId}`)
  return res.data
}

export async function updateStatus(id: string, body: StatusUpdate): Promise<Assignment> {
  const res = await api.put<Assignment>(`/api/delivery/shipper/assignments/${id}/status`, body)
  return res.data
}

export async function retryDelivery(id: string, expectedVersion: number): Promise<Assignment> {
  const res = await api.post<Assignment>(`/api/delivery/shipper/assignments/${id}/retry`, {
    expectedVersion,
  })
  return res.data
}

export async function postLocation(
  id: string,
  point: { latitude: number; longitude: number },
): Promise<Assignment> {
  const res = await api.post<Assignment>(`/api/delivery/shipper/assignments/${id}/location`, point)
  return res.data
}
