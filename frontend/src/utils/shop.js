export const shopStatusLabels = {
  PENDING: 'Chờ duyệt',
  ACTIVE: 'Đang hoạt động',
  REJECTED: 'Bị từ chối',
  SUSPENDED: 'Đang bị đình chỉ',
}

export const shopStatusClass = {
  PENDING: 'pending',
  ACTIVE: 'active',
  REJECTED: 'rejected',
  SUSPENDED: 'suspended',
}

export const orderStatusLabels = {
  PENDING: 'Chờ xác nhận',
  WAITING_PAYMENT: 'Chờ thanh toán',
  PROCESSING: 'Đang xử lý',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  CANCELLED: 'Đã hủy',
}

const currency = new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: 'VND',
  maximumFractionDigits: 0,
})

export const formatCurrency = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? currency.format(number) : '—'
}

export const formatCompactCurrency = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  if (Math.abs(number) >= 1_000_000_000)
    return `${(number / 1_000_000_000).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} tỷ`
  if (Math.abs(number) >= 1_000_000)
    return `${(number / 1_000_000).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} tr`
  if (Math.abs(number) >= 1_000)
    return `${(number / 1_000).toLocaleString('vi-VN', { maximumFractionDigits: 0 })} nghìn`
  return currency.format(number)
}

export const formatPercent = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return '—'
  return `${number.toLocaleString('vi-VN', { maximumFractionDigits: 1 })}%`
}

export const percentChange = (current, previous) => {
  const currentNumber = Number(current) || 0
  const previousNumber = Number(previous) || 0
  if (previousNumber === 0) return currentNumber === 0 ? 0 : null
  return ((currentNumber - previousNumber) / previousNumber) * 100
}

export const imageUrlOrEmpty = (url) =>
  typeof url === 'string' && /^https?:\/\//i.test(url) ? url : ''

const VIDEO_URL = /\.(mp4|webm|mov)(\?|#|$)/i

/** Minh chung tra hang co the la video (MP4/WebM/MOV); nhan dien theo duoi tep. */
export const isVideoUrl = (url) => VIDEO_URL.test(url || '')

export const shopAddress = (shop) =>
  [shop.streetAddress, shop.ward, shop.province].filter(Boolean).join(', ')

export const formatShortDate = (value) =>
  value
    ? new Intl.DateTimeFormat('vi-VN', {
        timeZone: 'Asia/Ho_Chi_Minh',
        day: '2-digit',
        month: '2-digit',
      }).format(new Date(value))
    : ''
