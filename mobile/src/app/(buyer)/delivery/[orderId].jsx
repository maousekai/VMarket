import { Link, useLocalSearchParams } from 'expo-router'
import { Linking, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { shortOrderId } from '../../../api/deliveryApi'
import DeliveryMap from '../../../buyer/DeliveryMap'
import useBuyerDelivery from '../../../buyer/useBuyerDelivery'
import { Badge, Button, Card, Loading, Notice } from '../../../components/ui'
import {
  BUYER_DELIVERY_STATUS_LABELS,
  canBuyerTrack,
  displayDate,
  telUrl,
} from '../../../deliveryStatus'
import { colors, spacing } from '../../../theme'

export default function BuyerDeliveryScreen() {
  const { orderId } = useLocalSearchParams()
  const state = useBuyerDelivery(typeof orderId === 'string' ? orderId : null)
  const { assignment, history } = state
  const phone = telUrl(assignment?.shipper?.phone)
  const showMap = state.authorized && canBuyerTrack(assignment?.status)
  const status = (value) => BUYER_DELIVERY_STATUS_LABELS[value] || 'Đang xử lý'
  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <ScrollView contentContainerStyle={styles.content}>
        <Link href="/(buyer)/orders" dismissTo style={styles.link}>
          Đơn của tôi
        </Link>
        <Text style={styles.title} accessibilityRole="header">
          Theo dõi đơn {shortOrderId(orderId)}
        </Text>
        {state.loading ? <Loading /> : null}
        {state.error && <Notice tone="danger">{state.error}</Notice>}
        {!state.loading && !assignment && !state.error && <Notice>Đơn chưa có chuyến giao.</Notice>}
        {assignment && (
          <>
            <Card>
              <Badge label={status(assignment.status)} />
              {assignment.earliestRetryAt && (
                <Text style={styles.value}>
                  Dự kiến giao lại từ {displayDate(assignment.earliestRetryAt)}
                </Text>
              )}
              {assignment.shipper?.name && (
                <Text style={styles.value}>Shipper {assignment.shipper.name}</Text>
              )}
              {phone && (
                <Button
                  title={`Gọi ${assignment.shipper.phone}`}
                  variant="secondary"
                  onPress={() => Linking.openURL(phone).catch(() => {})}
                />
              )}
              {showMap && (
                <>
                  <DeliveryMap
                    location={assignment.location}
                    deliveryPoint={assignment.deliveryPoint}
                  />
                  <Text style={styles.value}>
                    {assignment.location?.recordedAt
                      ? `Vị trí lúc ${displayDate(assignment.location.recordedAt)}`
                      : 'Đang chờ vị trí shipper'}
                    {state.live ? '' : ' · Đang kết nối'}
                  </Text>
                </>
              )}
            </Card>
            {history.length > 0 && (
              <Card>
                <Text style={styles.heading}>Lịch sử giao hàng</Text>
                {history.map((entry, index) => (
                  <View key={`${entry.occurred_at}-${index}`} style={styles.historyRow}>
                    <Text style={styles.heading}>{status(entry.status)}</Text>
                    {entry.public_reason && <Text style={styles.value}>{entry.public_reason}</Text>}
                    <Text style={styles.value}>{displayDate(entry.occurred_at)}</Text>
                  </View>
                ))}
              </Card>
            )}
          </>
        )}
        {!state.loading && <Button title="Cập nhật" variant="secondary" onPress={state.retry} />}
      </ScrollView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { padding: spacing(4), gap: spacing(3) },
  title: { fontSize: 22, fontWeight: '800', color: colors.text },
  heading: { fontWeight: '700', color: colors.text },
  value: { color: colors.muted },
  historyRow: { gap: spacing(1) },
  link: { color: colors.primary, fontWeight: '600' },
})
