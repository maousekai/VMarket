import { Link } from 'expo-router'
import { StyleSheet, Text } from 'react-native'
import { listBuyerNotifications } from '../../api/buyerApi'
import BuyerList from '../../buyer/BuyerList'
import { Card } from '../../components/ui'
import { displayDate } from '../../deliveryStatus'
import { colors } from '../../theme'

function renderNotification({ item }) {
  const orderId = /^\/orders\/([A-Za-z0-9_-]+)$/.exec(item.link || '')?.[1]
  return (
    <Card>
      <Text style={styles.title}>{item.title}</Text>
      <Text style={styles.message}>{item.message}</Text>
      {item.createdAt && <Text style={styles.message}>{displayDate(item.createdAt)}</Text>}
      {orderId && (
        <Link
          href={{ pathname: '/(buyer)/delivery/[orderId]', params: { orderId } }}
          style={styles.link}
        >
          Xem đơn hàng
        </Link>
      )}
    </Card>
  )
}

export default function NotificationsScreen() {
  return (
    <BuyerList
      title="Thông báo"
      fetchPage={listBuyerNotifications}
      renderItem={renderNotification}
      emptyMessage="Bạn chưa có thông báo."
    />
  )
}

const styles = StyleSheet.create({
  title: { color: colors.text, fontWeight: '700', fontSize: 16 },
  message: { color: colors.muted },
  link: { color: colors.primary, fontWeight: '600', paddingVertical: 8 },
})
