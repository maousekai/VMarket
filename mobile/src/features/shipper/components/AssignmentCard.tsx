import { router } from 'expo-router'
import { View } from 'react-native'

import type { Assignment, DeliveryStatus } from '@/api/delivery'
import { AppText, Button, StatusBadge } from '@/components/ui'

import { taskHref } from '../actions'
import { orderLine, parseOrder } from '../lib/order'
import { STATUS_LABEL, STATUS_TONE } from '../lib/status'
import { LinesCard, PhoneLink } from './cards'

const CTA: Partial<Record<DeliveryStatus, string>> = {
  ASSIGNED: 'Xem chi tiết đơn',
  PICKED_UP: 'Tiếp tục giao',
  IN_TRANSIT: 'Tiếp tục giao',
  FAILED: 'Giao lại',
  RETURNING: 'Hoàn về gian hàng',
}

/** One assignment of the "Đơn được phân công" list (frame 3.11, repeated per order). */
export function AssignmentCard({ assignment: a }: { assignment: Assignment }) {
  const { receiver } = parseOrder(a.orderSnapshot)
  return (
    <View testID="shipper-assignment" style={{ gap: 12 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <AppText variant="label" style={{ flex: 1 }} numberOfLines={1}>
          {orderLine(a)}
        </AppText>
        <StatusBadge
          compact
          label={STATUS_LABEL[a.status] ?? a.status}
          tone={STATUS_TONE[a.status] ?? 'neutral'}
        />
      </View>
      <LinesCard title={`Giao cho ${receiver.name || 'người nhận'}`} lines={[receiver.address]}>
        <PhoneLink phone={receiver.phone} />
      </LinesCard>
      <Button
        testID="shipper-open-task"
        title={CTA[a.status] ?? 'Xem chi tiết đơn'}
        onPress={() => router.push(taskHref(a.orderId))}
      />
    </View>
  )
}
