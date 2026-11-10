import { router, useLocalSearchParams, type Href } from 'expo-router'

import { AppText, Button } from '@/components/ui'
import { taskHref } from '@/features/shipper/actions'
import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { LinesCard } from '@/features/shipper/components/cards'
import { orderCode } from '@/features/shipper/lib/order'
import { STATUS_LABEL } from '@/features/shipper/lib/status'
import { buildTimeline } from '@/features/shipper/lib/timeline'

/** Frame 3.27: journey timeline of one order, built from the status history rows. */
export default function ShipperJourneyScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  return (
    <DeliveryScreen orderId={orderId} testID="shipper-journey">
      {({ assignment: a, history }) => {
        const lines = buildTimeline(history, a)
        return (
          <>
            <AppText variant="h2" accessibilityRole="header">
              Lịch sử giao đơn
            </AppText>
            <LinesCard
              testID="shipper-journey-card"
              title={`${orderCode(a.orderId)} · ${STATUS_LABEL[a.status] ?? a.status}`}
              lines={lines.length ? lines : ['Chưa có lịch sử hành trình.']}
            />
            <Button
              testID="shipper-journey-confirmation"
              title={
                a.status === 'RETURNED'
                  ? 'Xem bàn giao hoàn về'
                  : a.status === 'DELIVERED'
                    ? 'Xem xác nhận giao hàng'
                    : 'Xem đơn'
              }
              onPress={() => router.push(taskHref(a.orderId))}
            />
            <Button
              testID="shipper-journey-home"
              kind="secondary"
              title="Về đơn được phân công"
              onPress={() => router.navigate('/(shipper)' as Href)}
            />
          </>
        )
      }}
    </DeliveryScreen>
  )
}
