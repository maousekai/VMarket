import { router } from 'expo-router'
import { useState } from 'react'

import type { DeliveryDetail } from '@/api/delivery'
import { Button, ConfirmDialog } from '@/components/ui'

import { goToTask, useDeliveryMutations } from '../actions'
import { LinesCard } from '../components/cards'
import { isPrepaid, orderCode } from '../lib/order'
import { ScreenTitle, useTaskData } from './common'

/** Frame 3.28: return address per shop + "Đã bàn giao cho gian hàng" (RETURNING -> RETURNED). */
export function HandoverView({ detail }: { detail: DeliveryDetail }) {
  const { a, order, shops, labels } = useTaskData(detail)
  const { status, recover } = useDeliveryMutations(a.orderId)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  const handover = async () => {
    if (busy) return
    setBusy(true)
    try {
      await status.mutateAsync({
        id: a.id,
        body: { status: 'RETURNED', expectedVersion: a.version },
      })
      setConfirming(false)
      goToTask(a.orderId)
    } catch (e) {
      setConfirming(false)
      const err = await recover(e)
      if (err.status === 409) goToTask(a.orderId)
    } finally {
      setBusy(false)
    }
  }

  const codLine = `${orderCode(a.orderId)} · ${isPrepaid(a) ? 'Đã thanh toán trước' : 'Chưa thu COD'}`

  return (
    <>
      <ScreenTitle>Bàn giao hoàn về</ScreenTitle>
      {(shops.length ? shops : [null]).map((s) => (
        <LinesCard
          key={s?.shopId ?? 'shop'}
          testID="shipper-handover-card"
          title={s?.name ?? 'Gian hàng'}
          lines={[
            s?.address,
            ...order.items
              .filter((i) => !s || i.shopId === s.shopId)
              .map((i) => `${labels[i.variantId] || 'Sản phẩm'} ×${i.quantity}`),
            codLine,
            'Chỉ xác nhận khi gian hàng đã nhận lại hàng.',
          ]}
        />
      ))}
      <Button
        testID="shipper-handover-confirm"
        title="Đã bàn giao cho gian hàng"
        onPress={() => setConfirming(true)}
      />
      <Button
        testID="shipper-handover-back"
        kind="secondary"
        title="Quay lại"
        onPress={() => router.back()}
      />
      <ConfirmDialog
        visible={confirming}
        title="Xác nhận bàn giao?"
        message="Chỉ xác nhận khi gian hàng đã nhận lại hàng."
        confirmLabel="Xác nhận"
        cancelLabel="Huỷ"
        loading={busy}
        onConfirm={handover}
        onCancel={() => setConfirming(false)}
      />
    </>
  )
}
