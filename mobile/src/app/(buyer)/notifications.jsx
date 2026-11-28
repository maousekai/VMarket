import { StyleSheet, Text } from 'react-native'
import { listBuyerNotifications } from '../../api/buyerApi'
import BuyerList from '../../buyer/BuyerList'
import { Card } from '../../components/ui'
import { colors } from '../../theme'

function renderNotification({ item }) {
  return (
    <Card>
      <Text style={styles.title}>{item.title}</Text>
      <Text style={styles.message}>{item.message}</Text>
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
})
