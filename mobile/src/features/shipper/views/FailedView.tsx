import { Ionicons } from '@expo/vector-icons'
import { useEffect, useState } from 'react'
import { Pressable, View } from 'react-native'

import { getApiError } from '@/api/client'
import type { DeliveryDetail } from '@/api/delivery'
import { AppText, BottomSheet, Button, Card, TextField } from '@/components/ui'
import { colors } from '@/theme'

import { goToTask, useDeliveryMutations } from '../actions'
import { LinesCard } from '../components/cards'
import { buildFailureReason, FAILURE_REASONS, OTHER_REASON } from '../lib/failure'
import { orderCode, orderLine } from '../lib/order'
import { isLivePending, type PendingWrite } from '../lib/pending'
import { MAX_ATTEMPTS } from '../lib/status'
import { ScreenTitle } from './common'

interface Props {
  detail: DeliveryDetail
  pending: PendingWrite | null
  savePending: (write: PendingWrite) => Promise<void>
  clearPending: () => Promise<void>
}

const VARIANT = {
  1: { title: 'Giao thất bại', button: 'Lưu lý do giao thất bại', card: false },
  2: { title: 'Giao lại chưa thành công', button: 'Lưu lý do · giao lại lần 2', card: true },
  3: { title: 'Không thể giao đơn', button: 'Lưu lý do · hoàn về', card: true },
} as const

/**
 * Frames 3.16 / 3.24 / 3.26 (one form, three attempt variants) and 3.93 (save result unknown).
 * reason + note are joined into the single <= 255 char `reason` column. On the second attempt the
 * redelivery is started right after the failure is saved; if that call fails the order is left
 * FAILED and the detail screen offers "Bắt đầu giao lại" (3.17).
 */
export function FailedView({ detail, pending, savePending, clearPending }: Props) {
  const a = detail.assignment
  const live = isLivePending(pending, a) ? pending : null
  useEffect(() => {
    if (pending && !live) void clearPending()
  }, [pending, live, clearPending])
  const { status, retry, recover } = useDeliveryMutations(a.orderId)
  const [reason, setReason] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [sheet, setSheet] = useState(false)
  const [busy, setBusy] = useState(false)
  const variant = VARIANT[Math.min(Math.max(a.attempt, 1), MAX_ATTEMPTS) as 1 | 2 | 3]
  const needsNote = reason === OTHER_REASON && !note.trim()

  const save = async (write: PendingWrite) => {
    if (busy) return
    setBusy(true)
    try {
      const after = await status.mutateAsync({
        id: write.assignmentId,
        body: { status: 'FAILED', reason: write.reason, expectedVersion: write.expectedVersion },
      })
      await clearPending()
      if (after.status === 'FAILED' && write.attempt === 2) {
        try {
          await retry.mutateAsync({ id: after.id, version: after.version })
        } catch (e) {
          await recover(e)
        }
      }
      goToTask(a.orderId)
    } catch (e) {
      const err = getApiError(e)
      if (err.retryable) {
        await savePending(write)
      } else {
        await recover(e)
        if (err.status === 409) goToTask(a.orderId)
      }
    } finally {
      setBusy(false)
    }
  }

  const submit = () => {
    if (!reason || needsNote) return
    void save({
      kind: 'failed',
      assignmentId: a.id,
      expectedVersion: a.version,
      reason: buildFailureReason(reason, note),
      attempt: a.attempt,
    })
  }

  if (live) {
    return (
      <>
        <ScreenTitle>Kết nối bị gián đoạn</ScreenTitle>
        <LinesCard
          testID="shipper-failed-pending-card"
          title="Dữ liệu đang chờ xác nhận"
          lines={[
            orderCode(a.orderId),
            `Lý do đã nhập: ${live.reason ?? ''}. Chưa tăng số lần giao lại khi chưa lưu thành công.`,
          ]}
        />
        <Button
          testID="shipper-failed-retry-save"
          title="Kiểm tra và thử lưu lại"
          loading={busy}
          onPress={() => void save(live)}
        />
        <Button
          testID="shipper-failed-view-task"
          kind="secondary"
          title="Xem đơn đang giao"
          disabled={busy}
          onPress={() => goToTask(a.orderId)}
        />
      </>
    )
  }

  const fields = (
    <>
      <TextField
        testID="shipper-failed-reason"
        label="Lý do giao thất bại"
        required
        picker
        value={reason ?? ''}
        placeholder="Chọn lý do"
        onPress={() => setSheet(true)}
      />
      {a.attempt >= MAX_ATTEMPTS ? (
        <AppText variant="body">Đã dùng đủ 2 lần giao lại. Đơn cần hoàn về gian hàng.</AppText>
      ) : null}
      <TextField
        testID="shipper-failed-note"
        label="Ghi chú"
        value={note}
        onChangeText={setNote}
        placeholder="Nhập ghi chú"
        helper={needsNote ? 'Vui lòng nhập ghi chú khi chọn lý do Khác' : undefined}
        maxLength={150}
        multiline
      />
    </>
  )

  return (
    <>
      <ScreenTitle>{variant.title}</ScreenTitle>
      {a.attempt === 1 ? <AppText variant="label">{orderLine(a)}</AppText> : null}
      {variant.card ? <Card>{fields}</Card> : fields}
      <Button
        testID="shipper-failed-save"
        title={variant.button}
        loading={busy}
        disabled={!reason || needsNote}
        onPress={submit}
      />
      <BottomSheet visible={sheet} onClose={() => setSheet(false)} title="Lý do giao thất bại">
        <View>
          {FAILURE_REASONS.map((r) => (
            <Pressable
              key={r}
              testID={`shipper-reason-${r}`}
              accessibilityRole="radio"
              accessibilityState={{ selected: r === reason }}
              onPress={() => {
                setReason(r)
                setSheet(false)
              }}
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
                minHeight: 48,
              }}
            >
              <AppText variant="body">{r}</AppText>
              {r === reason ? (
                <Ionicons name="checkmark" size={20} color={colors.primary600} />
              ) : null}
            </Pressable>
          ))}
        </View>
      </BottomSheet>
    </>
  )
}
