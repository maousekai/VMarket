import { useState } from 'react'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'

import { goToTask, useDeliveryMutations } from '../actions'
import { PickupCards, ReceiverCard } from '../components/cards'
import { orderLine } from '../lib/order'
import { ScreenTitle, useTaskData } from './common'

/**
 * Frame 3.13. The backend needs PICKED_UP before IN_TRANSIT and the design has a single button, so
 * "Xác nhận đã lấy hàng" sends both writes in sequence; if the second fails the order stays
 * PICKED_UP and the button resumes with the remaining step.
 */
export function PickupView({ detail }: { detail: DeliveryDetail }) {
  const { a, order, shops, labels } = useTaskData(detail)
  const { status, recover } = useDeliveryMutations(a.orderId)
  const [busy, setBusy] = useState(false)

  const confirm = async () => {
    if (busy) return
    setBusy(true)
    try {
      let cur = a
      if (cur.status === 'ASSIGNED') {
        cur = await status.mutateAsync({
          id: cur.id,
          body: { status: 'PICKED_UP', expectedVersion: cur.version },
        })
      }
      if (cur.status === 'PICKED_UP') {
        cur = await status.mutateAsync({
          id: cur.id,
          body: { status: 'IN_TRANSIT', expectedVersion: cur.version },
        })
      }
      goToTask(a.orderId)
    } catch (e) {
      const err = await recover(e)
      if (err.status === 409) goToTask(a.orderId)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <ScreenTitle>Lấy hàng</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <PickupCards shops={shops} items={order.items} labels={labels} />
      <ReceiverCard receiver={order.receiver} />
      <AppText variant="body">Kiểm tra đúng mặt hàng, số lượng và tình trạng gói hàng.</AppText>
      <Button
        testID="shipper-confirm-pickup"
        title={a.status === 'PICKED_UP' ? 'Bắt đầu giao' : 'Xác nhận đã lấy hàng'}
        loading={busy}
        onPress={confirm}
      />
    </>
  )
}
