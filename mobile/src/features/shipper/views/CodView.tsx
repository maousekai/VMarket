import { router } from 'expo-router'
import { useEffect, useState } from 'react'
import { View } from 'react-native'

import { getApiError } from '@/api/client'
import type { DeliveryDetail } from '@/api/delivery'
import { AppText, Button, useToast } from '@/components/ui'
import { formatVnd } from '@/lib/format'

import { goToTask, useDeliveryMutations } from '../actions'
import { LinesCard } from '../components/cards'
import { isLivePending, type PendingWrite } from '../lib/pending'
import { isPrepaid, orderCode, orderLine } from '../lib/order'
import { ScreenTitle } from './common'

interface Props {
  detail: DeliveryDetail
  pending: PendingWrite | null
  savePending: (write: PendingWrite) => Promise<void>
  clearPending: () => Promise<void>
}

/**
 * Frame 3.15 (confirm exact COD) and 3.92 (result unknown after a lost connection).
 * Never lets the shipper submit again while a write is unconfirmed: the only way forward is to
 * check the server state (the PUT is idempotent for the same `expectedVersion`).
 */
export function CodView({ detail, pending, savePending, clearPending }: Props) {
  const a = detail.assignment
  const live = isLivePending(pending, a) ? pending : null
  useEffect(() => {
    if (pending && !live) void clearPending()
  }, [pending, live, clearPending])
  const { status, recover, refresh } = useDeliveryMutations(a.orderId)
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const prepaid = isPrepaid(a)
  const amount = formatVnd(a.codAmount)

  const put = (expectedVersion: number) =>
    status.mutateAsync({
      id: a.id,
      body: { status: 'DELIVERED', codCollected: a.codAmount, expectedVersion },
    })

  const confirm = async () => {
    if (busy) return
    setBusy(true)
    try {
      await put(a.version)
      goToTask(a.orderId)
    } catch (e) {
      const err = getApiError(e)
      if (err.retryable) {
        await savePending({
          kind: 'cod',
          assignmentId: a.id,
          expectedVersion: a.version,
          amount: a.codAmount,
          attempt: a.attempt,
        })
      } else {
        await recover(e)
        if (err.status === 409) goToTask(a.orderId)
      }
    } finally {
      setBusy(false)
    }
  }

  const check = async () => {
    if (busy || !live) return
    setBusy(true)
    try {
      const current = (await refresh()).assignment
      if (current.status === 'IN_TRANSIT' && current.version === live.expectedVersion) {
        await put(live.expectedVersion)
      }
      await clearPending()
      goToTask(a.orderId)
    } catch (e) {
      if (getApiError(e).retryable) {
        toast.show({ type: 'error', message: 'Vẫn chưa kết nối được. Thử lại sau.' })
      } else {
        await recover(e)
      }
    } finally {
      setBusy(false)
    }
  }

  if (live) {
    return (
      <>
        <ScreenTitle>Kết nối bị gián đoạn</ScreenTitle>
        <LinesCard
          testID="shipper-cod-pending-card"
          title="Dữ liệu đang chờ xác nhận"
          lines={[
            prepaid
              ? `${orderCode(a.orderId)} · Đã giao hàng (đơn trả trước).`
              : `${orderCode(a.orderId)} · Đã thu ${formatVnd(live.amount ?? a.codAmount)} tiền mặt.`,
            'Chưa xác nhận được kết quả lưu. Không thu tiền lần nữa; kiểm tra trạng thái trước khi tiếp tục.',
          ]}
        />
        <Button
          testID="shipper-cod-check"
          title="Kiểm tra trạng thái COD"
          loading={busy}
          onPress={check}
        />
        <Button
          testID="shipper-cod-view-task"
          kind="secondary"
          title="Xem đơn đang giao"
          disabled={busy}
          onPress={() => goToTask(a.orderId)}
        />
      </>
    )
  }

  return (
    <>
      <ScreenTitle>Xác nhận COD</ScreenTitle>
      <AppText variant="label">{orderLine(a)}</AppText>
      <AppText variant="display" color="primary700" testID="shipper-cod-amount">
        {amount}
      </AppText>
      <LinesCard
        title="Trước khi xác nhận"
        lines={[
          prepaid
            ? 'Chỉ bấm khi người nhận đã nhận hàng. Đơn đã thanh toán trước, không thu tiền mặt.'
            : `Chỉ bấm khi người nhận đã nhận hàng và bạn đã thu đủ ${amount} tiền mặt.`,
        ]}
      />
      <View style={{ gap: 12 }}>
        <Button
          testID="shipper-cod-confirm"
          title={prepaid ? 'Đã giao hàng' : `Đã thu đủ ${amount}`}
          loading={busy}
          onPress={confirm}
        />
        <Button
          testID="shipper-cod-back"
          kind="secondary"
          title="Chưa thu đủ tiền"
          disabled={busy}
          onPress={() => router.back()}
        />
      </View>
    </>
  )
}
