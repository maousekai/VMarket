// Kieu anh ma backend nhan (product/review upload va AI Search tim bang anh).
export const ACCEPTED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const ACCEPTED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp']
export const IMAGE_ACCEPT = ACCEPTED_IMAGE_TYPES.join(',')

/** Mot so trinh duyet de trong `type` voi anh dan tu clipboard: doan theo duoi file. */
export function isAcceptedImage(file) {
  const extension = file?.name?.split('.').pop()?.toLocaleLowerCase('en')
  return (
    ACCEPTED_IMAGE_TYPES.includes(file?.type) ||
    (!file?.type && ACCEPTED_EXTENSIONS.includes(extension))
  )
}

/** "2,4 MB" theo cach doc cua nguoi dung (1 MB = 1.000.000 byte, khop gioi han Search). */
export function formatMegabytes(bytes) {
  const value = bytes / 1_000_000
  return `${value.toLocaleString('vi-VN', { maximumFractionDigits: value < 10 ? 1 : 0 })} MB`
}
