import { useMutation, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'
import { useState } from 'react'

import { getApiError } from '@/api/client'
import { MAX_EVIDENCE, RETURN_WINDOW_DAYS, createReturn } from '@/api/returns'
import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  Button,
  ConfirmDialog,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
  TextField,
  useToast,
} from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { ChoiceSheet } from '@/features/orders/components/ChoiceSheet'
import { MediaPicker } from '@/features/orders/components/MediaPicker'
import {
  orderKeys,
  useDeliveredAt,
  useItemLabels,
  useOrder,
  useOrderReturns,
} from '@/features/orders/hooks'
import {
  ImageTooLargeError,
  encodeImages,
  pickImages,
  type PickedImage,
} from '@/features/orders/media'
import { backOr, orderPath, returnPath } from '@/features/orders/nav'
import { daysLeft, itemKey, itemOneLine, orderCode, shortDate } from '@/features/orders/text'

const REASONS = ['Sản phẩm lỗi', 'Không đúng mô tả', 'Giao thiếu/sai sản phẩm', 'Khác']

export default function ReturnRequestScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { status: auth } = useAuth()
  const authed = auth === 'authenticated'
  const toast = useToast()
  const qc = useQueryClient()

  const q = useOrder(id, authed)
  const order = q.data
  const returns = useOrderReturns(id, authed)
  const deliveredAt = useDeliveredAt(order)
  const { label } = useItemLabels(order?.items ?? [])

  const [pickedKey, setItemKey] = useState<string | null>(null)
  const [reason, setReason] = useState<string | null>(null)
  const [description, setDescription] = useState('')
  const [files, setFiles] = useState<PickedImage[]>([])
  const [sheet, setSheet] = useState<'item' | 'reason' | null>(null)
  const [discard, setDiscard] = useState(false)
  const [windowExpired, setWindowExpired] = useState(false)

  const returned = new Set(returns.data?.flatMap((r) => r.items.map((i) => i.orderItemId)))
  const available = (order?.items ?? []).filter((i) => !i.id || !returned.has(i.id))
  const fallbackKey = available[0] ? itemKey(available[0]) : null
  const selected = available.find((i) => itemKey(i) === (pickedKey ?? fallbackKey))
  const left = daysLeft(deliveredAt, RETURN_WINDOW_DAYS)
  const expired = windowExpired || (left !== null && left <= 0)
  const over = files.length > MAX_EVIDENCE
  const dirty = !!reason || !!description.trim() || files.length > 0

  const submit = useMutation({
    mutationFn: async () => {
      if (!selected?.id) throw new Error('MISSING_ORDER_ITEM_ID')
      const evidenceUrls = await encodeImages(files)
      const text = description.trim()
      return createReturn(id, {
        reason: (text ? `${reason}: ${text}` : (reason as string)).slice(0, 500),
        evidenceUrls,
        items: [
          {
            orderItemId: selected.id,
            productId: selected.productId,
            variantId: selected.variantId,
            quantity: selected.quantity,
          },
        ],
      })
    },
    onSuccess: (ret) => {
      qc.invalidateQueries({ queryKey: orderKeys.returns(id) })
      qc.invalidateQueries({ queryKey: orderKeys.detail(id) })
      router.replace(returnPath(ret.id))
    },
    onError: (e) => {
      if (e instanceof ImageTooLargeError) {
        return toast.show({ type: 'error', message: 'Ảnh quá lớn, hãy chọn ảnh nhỏ hơn.' })
      }
      const err = getApiError(e)
      if (err.code === 'RETURN_WINDOW_EXPIRED') return setWindowExpired(true)
      if (err.code === 'ITEM_ALREADY_RETURNED') returns.refetch()
      toast.show({ type: 'error', message: err.message })
    },
  })

  const back = () => (dirty && !submit.isPending ? setDiscard(true) : backOr(`/orders/${id}`)())
  const canSubmit = !!selected?.id && !!reason && files.length >= 1 && !over
  const repick = async () => setFiles(await pickImages(MAX_EVIDENCE))

  const body = () => {
    if (q.isPending || returns.isPending) return <Skeleton height={130} />
    if (q.isError || !order) return <ErrorState error={q.error} onRetry={() => q.refetch()} />
    const code = orderCode(order.id)
    const toOrder = (
      <Button title="Về đơn hàng" kind="secondary" onPress={() => router.replace(orderPath(id))} />
    )

    if (order.status !== 'DELIVERED') {
      return (
        <>
          <InfoCard
            title="Không thể tạo yêu cầu"
            body={`${code} chỉ có thể trả hàng khi đơn ở trạng thái Đã giao.`}
          />
          {toOrder}
        </>
      )
    }
    if (expired) {
      return (
        <>
          <InfoCard
            title="Không thể tạo yêu cầu"
            body={`${code} ${deliveredAt ? `đã giao ngày ${shortDate(deliveredAt)}, ` : ''}quá ${RETURN_WINDOW_DAYS} ngày cho phép trả hàng.`}
          />
          <Button
            testID="return-ask-ai"
            title="Hỏi trợ lý AI"
            onPress={() => router.push('/(buyer)/assistant' as Href)}
          />
          {toOrder}
        </>
      )
    }
    if (available.length === 0) {
      return (
        <>
          <InfoCard title="Không thể tạo yêu cầu" body="Mỗi sản phẩm chỉ yêu cầu một lần." />
          {toOrder}
        </>
      )
    }

    return (
      <>
        <AppText>{`${code} · ${left !== null ? `còn ${left} ngày để yêu cầu` : `trong ${RETURN_WINDOW_DAYS} ngày kể từ khi nhận hàng`}`}</AppText>
        {available.some((i) => !i.id) ? (
          <InfoCard
            testID="return-missing-id"
            title="Chưa thể gửi yêu cầu"
            body="Hệ thống chưa cung cấp mã dòng hàng nên ứng dụng chưa gửi được yêu cầu trả hàng. Vui lòng thử lại sau hoặc liên hệ hỗ trợ."
          />
        ) : null}

        <TextField
          label="Sản phẩm cần trả"
          required
          picker
          testID="return-item"
          value={selected ? itemOneLine(selected, label(selected)) : ''}
          onPress={available.length > 1 ? () => setSheet('item') : undefined}
        />
        <TextField
          label="Lý do"
          required
          picker
          testID="return-reason"
          placeholder="Chọn lý do"
          value={reason ?? ''}
          onPress={() => setSheet('reason')}
        />
        <TextField
          label="Mô tả thêm"
          testID="return-description"
          multiline
          maxLength={400}
          value={description}
          onChangeText={setDescription}
          placeholder="Mô tả vấn đề bạn gặp phải"
        />
        <MediaPicker
          testID="return-evidence"
          label="Ảnh/video minh chứng"
          required
          files={files}
          onChange={setFiles}
          max={MAX_EVIDENCE}
          unit="tệp"
          overTitle={`Tối đa ${MAX_EVIDENCE} ảnh/video`}
          overSuffix="để gửi yêu cầu."
          hint={`Tối đa ${MAX_EVIDENCE} ảnh/video. Mỗi sản phẩm chỉ yêu cầu một lần.`}
          disabled={submit.isPending}
        />

        {over ? (
          <>
            <Button testID="return-repick" title="Chọn lại tệp" onPress={repick} />
            <Button title="Quay lại" kind="secondary" onPress={() => setFiles([])} />
          </>
        ) : (
          <>
            <Button
              testID="return-submit"
              title="Gửi yêu cầu"
              disabled={!canSubmit}
              loading={submit.isPending}
              onPress={() => submit.mutate()}
            />
            <Button
              testID="return-cancel"
              title="Hủy"
              kind="secondary"
              disabled={submit.isPending}
              onPress={back}
            />
          </>
        )}
      </>
    )
  }

  return (
    <Screen testID="return-screen" title="Yêu cầu trả hàng" onBack={back}>
      <AuthGate>{body()}</AuthGate>
      <ChoiceSheet
        visible={sheet === 'item'}
        title="Sản phẩm cần trả"
        options={available.map((i) => ({ key: itemKey(i), label: itemOneLine(i, label(i)) }))}
        selected={selected ? itemKey(selected) : null}
        onSelect={setItemKey}
        onClose={() => setSheet(null)}
      />
      <ChoiceSheet
        visible={sheet === 'reason'}
        title="Lý do trả hàng"
        options={REASONS.map((r) => ({ key: r, label: r }))}
        selected={reason}
        onSelect={setReason}
        onClose={() => setSheet(null)}
      />
      <ConfirmDialog
        visible={discard}
        title="Bỏ yêu cầu trả hàng?"
        message="Thông tin bạn đã nhập sẽ không được lưu."
        confirmLabel="Bỏ yêu cầu"
        cancelLabel="Tiếp tục nhập"
        destructive
        onConfirm={() => {
          setDiscard(false)
          backOr(`/orders/${id}`)()
        }}
        onCancel={() => setDiscard(false)}
      />
    </Screen>
  )
}
