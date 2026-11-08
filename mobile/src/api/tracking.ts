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

export interface DeliveryAssignment {
  status: DeliveryStatus
  latitude: number | null
  longitude: number | null
  /** Time of the shipper's last GPS fix. */
  locationAt: string | null
}

export interface DeliveryHistoryEntry {
  status: DeliveryStatus
  attempt: number
  occurred_at: string | number
}

export interface DeliveryDetail {
  assignment: DeliveryAssignment
  history: DeliveryHistoryEntry[]
}

/** GET /api/delivery/orders/{id}: 404 DELIVERY_NOT_FOUND until the seller hands the order off. */
export async function getDelivery(orderId: string): Promise<DeliveryDetail> {
  return (await api.get<DeliveryDetail>(`/api/delivery/orders/${orderId}`)).data
}
