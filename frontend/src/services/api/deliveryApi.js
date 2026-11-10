import apiClient from './apiClient'

// Delivery Service (PBL6-20). Seller ban giao phan hang cua shop minh; nguoi mua / seller xem
// trang thai chuyen giao. 404 DELIVERY_NOT_FOUND = don chua co chuyen giao.
export const DELIVERY_STATUS_LABELS = Object.freeze({
  WAITING_HANDOFF: 'Chờ các gian hàng bàn giao',
  WAITING_ASSIGNMENT: 'Chờ phân công shipper',
  ASSIGNED: 'Đã có shipper nhận đơn',
  PICKED_UP: 'Shipper đã lấy hàng',
  IN_TRANSIT: 'Đang giao hàng',
  FAILED: 'Giao không thành công, sẽ giao lại',
  RETURNING: 'Đang hoàn hàng về gian hàng',
  RETURNED: 'Đã hoàn hàng',
  DELIVERED: 'Đã giao thành công',
})

const orderUrl = (orderId) => `/api/delivery/orders/${encodeURIComponent(orderId)}`

export const handoffOrder = async (orderId) =>
  (await apiClient.post(`/api/delivery/seller/orders/${encodeURIComponent(orderId)}/handoff`)).data

/** {assignment:{status,...}, history:[{status, occurred_at}], ...} */
export const getDelivery = async (orderId) => (await apiClient.get(orderUrl(orderId))).data

// TẠM THỜI (dev): đưa chuyến giao tới DELIVERED khi chưa có giao diện điều phối / shipper.
// Backend chỉ bật khi DELIVERY_DEV_SIMULATION=true. Xoá cùng nút ở SellerOrdersPage.
export const simulateDelivery = async (orderId) =>
  (
    await apiClient.post(
      `/api/delivery/seller/orders/${encodeURIComponent(orderId)}/simulate-delivery`,
    )
  ).data
