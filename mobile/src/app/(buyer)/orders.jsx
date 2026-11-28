import { Linking, StyleSheet, Text } from 'react-native'
import { listBuyerOrders } from '../../api/buyerApi'
import { formatMoney, shortOrderId } from '../../api/deliveryApi'
import BuyerList from '../../buyer/BuyerList'
import { Badge, Button, Card } from '../../components/ui'
import { WEB_URL } from '../../config'
import { colors } from '../../theme'

const LABELS = {
  WAITING_PAYMENT: 'Chờ thanh toán',
  PENDING: 'Chờ xác nhận',
  PROCESSING: 'Đang chuẩn bị',
  SHIPPED: 'Đang giao',
  DELIVERED: 'Đã giao',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã huỷ',
  DELIVERY_FAILED: 'Đang hoàn hàng',
  RETURNED_TO_SELLER: 'Đã hoàn hàng về shop',
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
      {WEB_URL && (
        <Button
          title="Xem đơn trên web"
          variant="secondary"
          onPress={() =>
            Linking.openURL(`${WEB_URL}/profile/orders/${encodeURIComponent(item.id)}`).catch(
              () => {},
            )
          }
        />
      )}
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
})
