import { router } from 'expo-router'
import { View } from 'react-native'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'
import { formatVnd } from '@/lib/format'

import { taskHref } from '../actions'
import { GpsBanner, LOCATION_ROUTE } from '../components/GpsBanner'
import { LinesCard, PhoneLink } from '../components/cards'
import { useTrackerState } from '../location/useLocation'
import { isPrepaid, orderCode, orderLine } from '../lib/order'
import { MAX_REDELIVERIES, redeliveryNumber } from '../lib/status'
import { ScreenTitle, useTaskData } from './common'

/** Frames 3.14 (first attempt) and 3.23 / 3.25 (redelivery 1/2 and 2/2): IN_TRANSIT. */
export function DeliveringView({ detail }: { detail: DeliveryDetail }) {
  const { a, order } = useTaskData(detail)
  const tracker = useTrackerState()
  const redelivery = a.attempt > 1
  const prepaid = isPrepaid(a)
  const { receiver } = order

  const shareLine =
    tracker.status === 'sharing'
      ? 'Đang chia sẻ vị trí khi giao hàng.'
      : tracker.status === 'lost'
        ? 'Vị trí chưa được chia sẻ. Kiểm tra GPS và kết nối mạng.'
        : 'Đang kết nối GPS để chia sẻ vị trí…'

  const contact = (
    <>
      <PhoneLink phone={receiver.phone} prefix={receiver.name ? `${receiver.name} · ` : ''} />
      <AppText variant="body">{receiver.address}</AppText>
    </>
  )

  return (
    <>
      <ScreenTitle>
        {redelivery ? `Đang giao lại · ${redeliveryNumber(a)}/${MAX_REDELIVERIES}` : 'Đang giao'}
      </ScreenTitle>
      <GpsBanner />
      {redelivery ? (
        <LinesCard
          testID="shipper-redelivery-card"
          title={a.attempt >= 3 ? 'Lần giao lại cuối' : orderCode(a.orderId)}
          lines={a.attempt >= 3 ? [orderCode(a.orderId)] : []}
        >
          {contact}
          <AppText variant="body">
            {prepaid ? 'Đã thanh toán trước' : `COD cần thu: ${formatVnd(a.codAmount)}`}
          </AppText>
        </LinesCard>
      ) : (
        <>
          <AppText variant="label">{orderLine(a)}</AppText>
          <LinesCard title="Điểm giao">{contact}</LinesCard>
          <LinesCard
            title={prepaid ? 'Đã thanh toán trước' : 'Cần thu COD'}
            lines={[prepaid ? '0đ — không thu tiền' : formatVnd(a.codAmount)]}
          />
        </>
      )}
      <AppText variant="body">{shareLine}</AppText>
      <View style={{ gap: 12 }}>
        <Button
          testID="shipper-deliver"
          title={
            redelivery
              ? 'Đã gặp người nhận'
              : prepaid
                ? 'Giao hàng và xác nhận'
                : 'Giao hàng và xác nhận COD'
          }
          onPress={() => router.push(taskHref(a.orderId, 'cod'))}
        />
        <Button
          testID="shipper-fail"
          kind="secondary"
          title={redelivery ? 'Giao lại không thành công' : 'Không giao được'}
          onPress={() => router.push(taskHref(a.orderId, 'failed'))}
        />
        {redelivery ? null : (
          <Button
            testID="shipper-gps-lost"
            kind="secondary"
            title="GPS mất kết nối"
            onPress={() => router.navigate(LOCATION_ROUTE)}
          />
        )}
      </View>
    </>
  )
}
