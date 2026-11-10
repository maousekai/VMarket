import { router, type Href } from 'expo-router'
import { useState } from 'react'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'

import { PickupCards, ReceiverCard } from '../components/cards'
import { useLocationPermission } from '../location/useLocation'
import { isPrepaid, orderLine } from '../lib/order'
import { taskHref } from '../actions'
import { ScreenTitle, useTaskData } from './common'

/** Frame 3.12: ASSIGNED order, before the shipper heads to the shop. */
export function AssignedView({ detail }: { detail: DeliveryDetail }) {
  const { a, order, shops, labels } = useTaskData(detail)
  const permission = useLocationPermission()
  // The permission explainer (3.18/3.21) is offered once; "Chưa cho phép" returns here and goes on.
  const [prompted, setPrompted] = useState(false)

  const start = () => {
    if (permission.state !== 'granted' && permission.state !== 'loading' && !prompted) {
      setPrompted(true)
      router.push('/(shipper)/location?back=1' as Href)
      return
    }
    router.push(taskHref(a.orderId, 'pickup'))
  }

  return (
    <>
      <ScreenTitle>Chi tiết đơn được phân công</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <PickupCards shops={shops} items={order.items} labels={labels} />
      <ReceiverCard receiver={order.receiver} />
      <AppText variant="body">
        {`Hệ thống đã phân công đơn này cho bạn theo khu vực. Đến gian hàng để lấy hàng; ${
          isPrepaid(a) ? 'đơn đã thanh toán trước.' : 'tiền COD thu khi giao thành công.'
        }`}
      </AppText>
      <Button testID="shipper-go-pickup" title="Đến lấy hàng" onPress={start} />
    </>
  )
}
