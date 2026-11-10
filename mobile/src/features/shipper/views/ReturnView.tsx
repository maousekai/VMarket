import { router } from 'expo-router'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'
import { formatVnd } from '@/lib/format'

import { taskHref } from '../actions'
import { GpsBanner } from '../components/GpsBanner'
import { LinesCard } from '../components/cards'
import { isPrepaid, orderLine } from '../lib/order'
import { MAX_ATTEMPTS, MAX_REDELIVERIES, redeliveryNumber } from '../lib/status'
import { ScreenTitle, useTaskData } from './common'

/** Frame 3.20: RETURNING, the parcel must go back to the shop(s). */
export function ReturnView({ detail }: { detail: DeliveryDetail }) {
  const { a, shops } = useTaskData(detail)
  const exhausted = a.attempt >= MAX_ATTEMPTS
  const shopNames = shops.map((s) => s.name).join(', ') || 'gian hàng'

  return (
    <>
      <ScreenTitle>Hoàn về gian hàng</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <GpsBanner />
      <LinesCard
        testID="shipper-return-card"
        title={exhausted ? 'Đã hết lượt giao lại' : 'Điều phối hoàn hàng'}
        lines={[
          exhausted
            ? `${redeliveryNumber(a)}/${MAX_REDELIVERIES} lần giao lại không thành công.`
            : 'Đơn được điều phối hoàn về gian hàng.',
          `Hàng cần hoàn về ${shopNames}.`,
        ]}
      />
      {isPrepaid(a) ? (
        <LinesCard
          title="Đã thanh toán trước"
          lines={['Đơn đã trả trước sẽ chuyển quy trình hoàn tiền cho người mua.']}
        />
      ) : (
        <LinesCard
          title="COD chưa thu"
          lines={[`Không ghi nhận đã thu ${formatVnd(a.codAmount)}.`]}
        />
      )}
      <Button
        testID="shipper-return-address"
        title="Xem địa chỉ hoàn hàng"
        onPress={() => router.push(taskHref(a.orderId, 'handover'))}
      />
    </>
  )
}
