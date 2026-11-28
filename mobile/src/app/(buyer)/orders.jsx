import { Link } from 'expo-router'
import { StyleSheet, Text } from 'react-native'
import { listBuyerOrders } from '../../api/buyerApi'
import { formatMoney, shortOrderId } from '../../api/deliveryApi'
import BuyerList from '../../buyer/BuyerList'
import { Badge, Card } from '../../components/ui'
import { colors } from '../../theme'

const LABELS = {
  WAITING_PAYMENT: 'Chờ thanh toán',
  PENDING: 'Chờ xác nhận',
  PROCESSING: 'Đang chuẩn bị',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã huỷ',
  DELIVERY_FAILED: 'Giao không thành công, đang hoàn về shop',
  RETURNED_TO_SELLER: 'Đã hoàn trả cho shop',
}

const fetchOrders = async (_page, signal) => ({
  items: await listBuyerOrders(signal),
  totalPages: 1,
})

function renderOrder({ item }) {
  return (
    <Card>
      <Text style={styles.title}>Đơn {shortOrderId(item.id)}</Text>
      <Badge label={LABELS[item.status] || item.status} />
      {item.totalAmount != null && (
        <Text style={styles.value}>{formatMoney(item.totalAmount)}</Text>
      )}
      <Link
        href={{ pathname: '/(buyer)/delivery/[orderId]', params: { orderId: item.id } }}
        style={styles.link}
      >
        Theo dõi giao hàng
      </Link>
    </Card>
  )
}

export default function OrdersScreen() {
  return (
    <BuyerList
      title="Đơn của tôi"
      fetchPage={fetchOrders}
      renderItem={renderOrder}
      emptyMessage="Bạn chưa có đơn hàng."
    />
  )
}

const styles = StyleSheet.create({
  title: { color: colors.text, fontWeight: '700', fontSize: 16 },
  value: { color: colors.text },
  link: { fontWeight: '600', color: colors.primary, paddingVertical: 8 },
})
