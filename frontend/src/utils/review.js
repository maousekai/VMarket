// Nhan va dinh dang diem danh gia dung chung cho cac man hinh Review (PBL6-35).
export const RATING_LABELS = Object.freeze({
  1: 'Rất tệ',
  2: 'Không hài lòng',
  3: 'Bình thường',
  4: 'Hài lòng',
  5: 'Tuyệt vời',
})

const ratingFormat = new Intl.NumberFormat('vi-VN', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

/** 4.666 -> "4,7"; gia tri khong hop le -> "0,0". */
export const formatRating = (value) => ratingFormat.format(Number(value) || 0)

/**
 * Trang cuoi con du lieu khi trang dang xem da rong vi so trang giam (muc cuoi vua roi khoi danh
 * sach sau khi phan hoi / an / khoi phuc). Trang con du lieu hoac trang dau thi giu nguyen.
 */
export function lastPage(result, page) {
  if (result?.items?.length || page <= 0) return page
  return Math.min(page, Math.max(0, (result?.totalPages || 0) - 1))
}
