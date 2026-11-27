// Bang nhan trang thai chuyen giao DUNG CHUNG voi web (frontend/src/utils/deliveryStatus.js),
// theo review PR 68/69 muc 1.3. Sua o day thi sua ca ben web.
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

/** Chuyen dang/da hoan hang: khong giao cho nguoi mua, khong thu COD. */
export const RETURN_STATUSES = Object.freeze(['RETURNING', 'RETURN_DROPPED_OFF', 'RETURNED'])
export const isReturnStatus = (status) => RETURN_STATUSES.includes(status)
