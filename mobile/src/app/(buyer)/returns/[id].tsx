import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Image } from 'expo-image'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { ScrollView, StyleSheet } from 'react-native'

import { getApiError } from '@/api/client'
import { SELLER_RESPONSE_HOURS, disputeReturn, getReturn, type OrderReturn } from '@/api/returns'
import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  BottomSheet,
  Button,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
  TextField,
  useToast,
} from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { useOrder } from '@/features/orders/hooks'
import { backOr, orderPath } from '@/features/orders/nav'
import { returnStatusInfo } from '@/features/orders/status'
import { returnCode, shortDateTime } from '@/features/orders/text'
import { formatVnd } from '@/lib/format'
import { radius } from '@/theme'

const returnKey = (id: string) => ['returns', id] as const
const DISPUTE_BLOCKED = ['ORDER_ALREADY_COMPLETED', 'CANNOT_DISPUTE_ADMIN_DECISION']

export default function ReturnStatusScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { status: auth } = useAuth()
  const authed = auth === 'authenticated'
  const toast = useToast()
  const qc = useQueryClient()
  const [dispute, setDispute] = useState(false)
  const [reason, setReason] = useState('')
  const [disputeBlocked, setDisputeBlocked] = useState(false)

  const q = useQuery({ queryKey: returnKey(id), queryFn: () => getReturn(id), enabled: authed })
  const ret = q.data
  const order = useOrder(ret?.orderId ?? '', authed && !!ret).data

  const send = useMutation({
    mutationFn: () => disputeReturn(id, reason.trim()),
    onSuccess: (next) => {
      qc.setQueryData(returnKey(id), next)
      setDispute(false)
      setReason('')
      toast.show({ type: 'success', message: 'Đã gửi khiếu nại lên Admin.' })
    },
    onError: (e) => {
      const err = getApiError(e)
      if (DISPUTE_BLOCKED.includes(err.code)) setDisputeBlocked(true)
      setDispute(false)
      toast.show({ type: 'error', message: err.message })
      q.refetch()
    },
  })

  const info = ret ? returnStatusInfo(ret.status) : null
  const canDispute = ret?.status === 'REJECTED' && !disputeBlocked && order?.status !== 'COMPLETED'
  const toOrder = () => ret && router.replace(orderPath(ret.orderId))

  return (
    <Screen
      testID="return-status-screen"
      title="Yêu cầu trả hàng"
      onBack={backOr('/orders')}
      refreshing={q.isRefetching}
      onRefresh={authed ? () => q.refetch() : undefined}
    >
      <AuthGate>
        {q.isPending ? (
          <>
            <Skeleton height={21} />
            <Skeleton height={130} />
          </>
        ) : q.isError || !ret || !info ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : (
          <>
            <AppText testID="return-summary">{`${returnCode(ret.id)} · ${info.label}`}</AppText>

            {ret.status === 'REJECTED' ? (
              <InfoCard
                title="Lý do từ người bán"
                body={ret.sellerNote || 'Người bán không nêu lý do.'}
              />
            ) : null}

            {ret.status === 'ADMIN_REJECTED' ? (
              <InfoCard
                title="Quyết định của Admin"
                body={ret.adminNote || 'Yêu cầu bị từ chối.'}
              />
            ) : null}

            {ret.status === 'RESOLVED' ? (
              <InfoCard
                testID="return-refund-card"
                title={`Đã hoàn ${formatVnd(ret.refundAmount)}`}
                body={`${shortDateTime(ret.resolvedAt ?? ret.updatedAt)} Admin xác nhận chuyển khoản`}
              />
            ) : (
              <InfoCard title="Tiến trình">
                {progressLines(ret).map((line) => (
                  <AppText key={line}>{line}</AppText>
                ))}
              </InfoCard>
            )}

            {['PENDING', 'APPROVED', 'DISPUTED'].includes(ret.status) ? (
              <InfoCard
                title="Hoàn tiền dự kiến"
                body={`${formatVnd(ret.refundAmount)} · ${
                  order?.paymentMethod === 'COD' ? 'hoàn tiền mặt' : 'chuyển khoản'
                } sau khi người bán nhận lại hàng`}
              />
            ) : null}

            {ret.evidenceUrls.length ? (
              <InfoCard title="Minh chứng đã gửi">
                <ScrollView
                  horizontal
                  showsHorizontalScrollIndicator={false}
                  contentContainerStyle={styles.row}
                >
                  {ret.evidenceUrls.map((u, i) => (
                    <Image key={i} source={{ uri: u }} style={styles.thumb} contentFit="cover" />
                  ))}
                </ScrollView>
              </InfoCard>
            ) : null}

            {canDispute ? (
              <Button
                testID="return-dispute"
                title="Khiếu nại lên Admin"
                onPress={() => setDispute(true)}
              />
            ) : null}
            <Button
              testID="return-to-order"
              title="Về đơn hàng"
              kind={canDispute ? 'secondary' : 'primary'}
              onPress={toOrder}
            />
          </>
        )}
      </AuthGate>

      <BottomSheet
        visible={dispute}
        onClose={() => !send.isPending && setDispute(false)}
        title="Khiếu nại lên Admin"
        testID="dispute-sheet"
      >
        <TextField
          label="Lý do khiếu nại"
          required
          multiline
          maxLength={500}
          testID="dispute-reason"
          value={reason}
          onChangeText={setReason}
          placeholder="Vì sao bạn không đồng ý với quyết định của người bán?"
        />
        <Button
          testID="dispute-send"
          title="Gửi khiếu nại"
          disabled={!reason.trim()}
          loading={send.isPending}
          onPress={() => send.mutate()}
        />
        <Button
          title="Hủy"
          kind="secondary"
          disabled={send.isPending}
          onPress={() => setDispute(false)}
        />
      </BottomSheet>
    </Screen>
  )
}

function progressLines(ret: OrderReturn): string[] {
  const lines = [`${shortDateTime(ret.createdAt)} Đã gửi yêu cầu`]
  const deadline = new Date(new Date(ret.createdAt).getTime() + SELLER_RESPONSE_HOURS * 3_600_000)
  switch (ret.status) {
    case 'PENDING':
      lines.push(
        `Người bán phản hồi trước ${shortDateTime(deadline)} (${SELLER_RESPONSE_HOURS} giờ)`,
        'Quá hạn sẽ tự chuyển Admin xử lý',
      )
      break
    case 'APPROVED':
      lines.push(`${shortDateTime(ret.updatedAt)} Người bán chấp nhận, chờ thu hồi hàng`)
      break
    case 'REJECTED':
      lines.push(`${shortDateTime(ret.updatedAt)} Người bán từ chối yêu cầu`)
      break
    case 'DISPUTED':
      lines.push(`${shortDateTime(ret.updatedAt)} Chuyển Admin xử lý`)
      break
    case 'ADMIN_REJECTED':
      lines.push(`${shortDateTime(ret.updatedAt)} Admin từ chối yêu cầu`)
      break
    case 'CANCELLED':
      lines.push(`${shortDateTime(ret.updatedAt)} Yêu cầu đã được hủy`)
      break
  }
  return lines
}

const styles = StyleSheet.create({
  row: { gap: 8 },
  thumb: { width: 64, height: 64, borderRadius: radius.md },
})
