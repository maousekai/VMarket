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
