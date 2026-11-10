import { useState } from 'react'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button } from '@/components/ui'

import { useDeliveryMutations } from '../actions'
import { LinesCard } from '../components/cards'
import { lowerFirst } from '../lib/failure'
import { orderLine } from '../lib/order'
import { MAX_REDELIVERIES } from '../lib/status'
import { lastFailureReason } from '../lib/timeline'
import { ScreenTitle } from './common'

/** Frame 3.17: FAILED with redeliveries left. "Bắt đầu giao lại" calls POST .../retry. */
export function RedeliverView({ detail }: { detail: DeliveryDetail }) {
  const a = detail.assignment
  const { retry, recover } = useDeliveryMutations(a.orderId)
  const [busy, setBusy] = useState(false)
  const reason = lastFailureReason(detail.history)

  const start = async () => {
    if (busy) return
    setBusy(true)
    try {
      await retry.mutateAsync({ id: a.id, version: a.version })
    } catch (e) {
      await recover(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <ScreenTitle>Giao lại</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <LinesCard
        testID="shipper-redeliver-card"
        title={`Giao lại lần ${a.attempt}/${MAX_REDELIVERIES}`}
        lines={[
          reason ? `Lý do trước: ${lowerFirst(reason)}.` : null,
          'Kiểm tra địa chỉ và liên hệ trước khi giao lại.',
        ]}
      />
      <Button testID="shipper-redeliver" title="Bắt đầu giao lại" loading={busy} onPress={start} />
      <AppText variant="body">Giao lại tối đa 2 lần. Quá số lần sẽ hoàn về gian hàng.</AppText>
    </>
  )
}
