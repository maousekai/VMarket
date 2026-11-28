import rules from './generated/delivery-rules.json'

export const DELIVERY_STATUS_LABELS = rules.statusLabels.staff
export const BUYER_DELIVERY_STATUS_LABELS = rules.statusLabels.buyer
export const FAILURE_REASONS = rules.failureReasons
export const MAX_ATTEMPTS = rules.retry.maxAttempts
export const isReturnStatus = (status) =>
  ['RETURNING', 'RETURN_DROPPED_OFF', 'RETURNED'].includes(status)
export const canBuyerTrack = (status) => ['PICKED_UP', 'IN_TRANSIT'].includes(status)

const dates = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' })
export const displayDate = (value) =>
  value && Number.isFinite(Date.parse(value)) ? dates.format(new Date(value)) : ''

export function directionsUrl(location, address) {
  const target =
    Number.isFinite(location?.latitude) && Number.isFinite(location?.longitude)
      ? `${location.latitude},${location.longitude}`
      : address
  return target
    ? `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(target)}`
    : ''
}

export function telUrl(phone) {
  const digits = String(phone || '').replace(/[^\d+]/g, '')
  return digits ? `tel:${digits}` : ''
}
