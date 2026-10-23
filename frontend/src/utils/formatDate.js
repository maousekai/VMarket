const zone = 'Asia/Ho_Chi_Minh'
const dateFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: zone,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})
const dateTimeFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: zone,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

// Dates always show in Vietnam time, whatever the browser's time zone is.
export const formatDate = (value) => (value ? dateFormat.format(new Date(value)) : '')
export const formatDateTime = (value) => (value ? dateTimeFormat.format(new Date(value)) : '')
