import { Ionicons } from '@expo/vector-icons'
import { StyleSheet, View } from 'react-native'

import type { Order } from '@/api/orders'
import type { DeliveryDetail } from '@/api/tracking'
import { AppText, Card } from '@/components/ui'
import { shortDateTime } from '@/features/orders/text'
import { colors } from '@/theme'

const FLOW = ['WAITING_PAYMENT', 'PENDING', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'COMPLETED']

interface Step {
  label: string
  done: boolean
  at?: string
}

/** The API keeps no status history, so steps come from the current status + delivery history times. */
export function buildSteps(order: Order, delivery: DeliveryDetail | null | undefined): Step[] {
  const at = (status: string) => {
    const h = delivery?.history.find((e) => e.status === status)
    return h ? shortDateTime(h.occurred_at) : undefined
  }
  const placed: Step = { label: 'Đặt hàng', done: true, at: shortDateTime(order.createdAt) }
  if (order.status === 'CANCELLED') return [placed, { label: 'Đã hủy', done: true }]
  const rank = FLOW.indexOf(order.status)
  const reached = (status: string) => rank >= FLOW.indexOf(status)
  const steps: Step[] = [placed]
  if (order.paymentMethod === 'PAYOS') {
    steps.push({ label: 'Thanh toán PayOS', done: reached('PENDING') })
  }
  steps.push(
    { label: 'Người bán xác nhận', done: reached('PROCESSING') },
    { label: 'Đang giao', done: reached('SHIPPED'), at: at('PICKED_UP') ?? at('IN_TRANSIT') },
    { label: 'Đã giao', done: reached('DELIVERED'), at: at('DELIVERED') },
    { label: 'Hoàn thành', done: reached('COMPLETED') },
  )
  return steps
}

export function OrderTimeline({
  order,
  delivery,
}: {
  order: Order
  delivery: DeliveryDetail | null | undefined
}) {
  const steps = buildSteps(order, delivery)
  return (
    <Card testID="order-timeline">
      <AppText variant="label">Trạng thái đơn hàng</AppText>
      {steps.map((s) => (
        <View key={s.label} style={styles.row}>
          <Ionicons
            name={s.done ? 'checkmark-circle' : 'ellipse-outline'}
            size={18}
            color={s.done ? colors.primary600 : colors.textSubtle}
          />
          <AppText color={s.done ? 'text' : 'textSubtle'} style={styles.flex}>
            {s.label}
          </AppText>
          {s.at ? (
            <AppText variant="caption" color="textSubtle">
              {s.at}
            </AppText>
          ) : null}
        </View>
      ))}
    </Card>
  )
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  flex: { flex: 1 },
})
