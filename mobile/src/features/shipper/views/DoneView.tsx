import { router, type Href } from 'expo-router'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'
import { formatDateTime, formatVnd } from '@/lib/format'

import { LinesCard } from '../components/cards'
import { isPrepaid, orderCode, orderLine } from '../lib/order'
import { ScreenTitle } from './common'

const LIST = '/(shipper)' as Href

/** Frames 3.19 (DELIVERED) and 3.29 (RETURNED): terminal states, location sharing has stopped. */
export function DoneView({ detail }: { detail: DeliveryDetail }) {
  const a = detail.assignment
  const prepaid = isPrepaid(a)

  if (a.status === 'RETURNED') {
    return (
      <>
        <ScreenTitle>Đã bàn giao hoàn về</ScreenTitle>
        <LinesCard
          testID="shipper-returned-card"
          title="Hàng đã về gian hàng"
          lines={[
            orderCode(a.orderId),
            prepaid ? null : `COD chưa thu: ${formatVnd(a.codAmount)}`,
            prepaid
              ? 'Đơn đã thanh toán trước sẽ được hoàn tiền cho người mua.'
              : 'Không có tiền COD để hoàn cho người mua.',
            'Chia sẻ vị trí đã dừng.',
          ]}
        />
        <Button
          testID="shipper-back-to-list"
          title="Về đơn được phân công"
          onPress={() => router.dismissTo(LIST)}
        />
      </>
    )
  }

  return (
    <>
      <ScreenTitle>Giao thành công</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <LinesCard
        testID="shipper-delivered-card"
        title={prepaid ? 'Đã giao' : 'Đã giao · đã thu COD'}
        lines={[
          prepaid ? null : `${formatVnd(a.codAmount)} đã được xác nhận`,
          formatDateTime(a.updatedAt),
        ]}
      />
      <AppText variant="body">Chia sẻ GPS cho đơn này đã dừng.</AppText>
      <Button
        testID="shipper-back-to-list"
        title="Về danh sách đơn"
        onPress={() => router.dismissTo(LIST)}
      />
    </>
  )
}
