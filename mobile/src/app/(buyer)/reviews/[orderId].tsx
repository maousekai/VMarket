import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'

import { getApiError } from '@/api/client'
import type { OrderItem } from '@/api/orders'
import {
  MAX_REVIEW_IMAGES,
  createReview,
  isReviewApiMissing,
  listPendingReviews,
} from '@/api/reviews'
import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  Button,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
  TextField,
  useToast,
} from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { MediaPicker } from '@/features/orders/components/MediaPicker'
import { RatingStars } from '@/features/orders/components/RatingStars'
import { orderKeys, useItemLabels, useOrder } from '@/features/orders/hooks'
import {
  ImageTooLargeError,
  encodeImages,
  pickImages,
  type PickedImage,
} from '@/features/orders/media'
import { backOr, orderPath, orderSubPath, reviewPath } from '@/features/orders/nav'
import { orderStatusInfo } from '@/features/orders/status'
import { itemKey, orderCode } from '@/features/orders/text'

const MISSING_API =
  'Hệ thống đánh giá chưa sẵn sàng nên chưa thể gửi đánh giá lúc này. Vui lòng thử lại sau.'

export default function ReviewScreen() {
  const { orderId, itemId } = useLocalSearchParams<{ orderId: string; itemId?: string }>()
  const { status: auth } = useAuth()
  const authed = auth === 'authenticated'
  const toast = useToast()
  const qc = useQueryClient()

  const q = useOrder(orderId, authed)
  const order = q.data
  const { label } = useItemLabels(order?.items ?? [])
  const pending = useQuery({
    queryKey: ['reviews', 'pending', orderId],
    queryFn: () => listPendingReviews(orderId),
    enabled: authed && order?.status === 'COMPLETED',
    retry: false,
  })

  const [rating, setRating] = useState(5)
  const [comment, setComment] = useState('')
  const [files, setFiles] = useState<PickedImage[]>([])
  const [done, setDone] = useState<{ item: OrderItem; rating: number }[]>([])
  const [unavailable, setUnavailable] = useState(false)

  const doneKeys = new Set(done.map((d) => itemKey(d.item)))
  const pendingIds = pending.data ? new Set(pending.data.map((p) => p.orderItemId)) : null
  const remaining = (order?.items ?? []).filter(
    (i) => !doneKeys.has(itemKey(i)) && (!pendingIds || !i.id || pendingIds.has(i.id)),
  )
  const current = remaining.find((i) => itemKey(i) === itemId) ?? remaining[0]
  const justDone = done[done.length - 1]
  const over = files.length > MAX_REVIEW_IMAGES

  const submit = useMutation({
    mutationFn: async () => {
      if (!current) return
      const imageUrls = await encodeImages(files)
      await createReview({
        orderId,
        orderItemId: current.id,
        productId: current.productId,
        rating,
        comment: comment.trim(),
        imageUrls,
      })
    },
    onSuccess: () => {
      if (!current) return
      setDone((d) => [...d, { item: current, rating }])
      setRating(5)
      setComment('')
      setFiles([])
      qc.invalidateQueries({ queryKey: ['reviews', 'pending', orderId] })
      qc.invalidateQueries({ queryKey: orderKeys.detail(orderId) })
    },
    onError: (e) => {
      if (e instanceof ImageTooLargeError) {
        return toast.show({ type: 'error', message: 'Ảnh quá lớn, hãy chọn ảnh nhỏ hơn.' })
      }
      if (isReviewApiMissing(e)) return setUnavailable(true)
      toast.show({ type: 'error', message: getApiError(e).message })
    },
  })

  const toOrder = (kind: 'primary' | 'secondary') => (
    <Button
      testID="review-to-order"
      title="Về đơn hàng"
      kind={kind}
      onPress={() => router.replace(orderPath(orderId))}
    />
  )

  const body = () => {
    if (q.isPending) return <Skeleton height={130} />
    if (q.isError || !order) return <ErrorState error={q.error} onRetry={() => q.refetch()} />

    if (order.status !== 'COMPLETED') {
      return (
        <>
          <InfoCard title="Đơn chưa hoàn thành">
            <AppText>{`${orderCode(order.id)} · ${orderStatusInfo(order.status).label}`}</AppText>
            <AppText>Bạn có thể đánh giá khi đơn chuyển Hoàn thành.</AppText>
          </InfoCard>
          <Button
            testID="review-track"
            title="Theo dõi đơn hàng"
            onPress={() =>
              router.replace(
                order.status === 'SHIPPED'
                  ? orderSubPath(order.id, 'tracking')
                  : orderPath(order.id),
              )
            }
          />
        </>
      )
    }
    if (unavailable || (pending.isError && isReviewApiMissing(pending.error) && !justDone)) {
      return (
        <>
          <InfoCard title="Chưa thể đánh giá" body={MISSING_API} />
          {toOrder('primary')}
        </>
      )
    }
    if (pending.isPending) return <Skeleton height={130} />

    if (!current) {
      return justDone ? (
        <>
          <ThanksCard done={justDone} label={label} />
          {toOrder('primary')}
        </>
      ) : (
        <>
          <InfoCard title="Đã đánh giá" body="Bạn đã đánh giá tất cả sản phẩm trong đơn này." />
          {toOrder('primary')}
        </>
      )
    }

    const l = label(current)
    return (
      <>
        {justDone ? (
          <>
            <ThanksCard done={justDone} label={label} />
            <Button
              testID="review-next"
              title="Đánh giá sản phẩm còn lại"
              onPress={() => router.replace(reviewPath(orderId, itemKey(current)))}
            />
            {toOrder('secondary')}
          </>
        ) : (
          <>
            <AppText testID="review-summary">
              {`${l.variant ? `${l.name} / ${l.variant}` : l.name} · ${orderCode(order.id)}`}
            </AppText>
            {over ? null : (
              <>
                <AppText variant="label">Chấm điểm *</AppText>
                <RatingStars value={rating} onChange={setRating} disabled={submit.isPending} />
                <TextField
                  label="Nhận xét"
                  testID="review-comment"
                  multiline
                  maxLength={500}
                  value={comment}
                  onChangeText={setComment}
                  placeholder="Chia sẻ cảm nhận của bạn"
                />
              </>
            )}
            <MediaPicker
              testID="review-images"
              label="Ảnh"
              files={files}
              onChange={setFiles}
              max={MAX_REVIEW_IMAGES}
              unit="ảnh"
              overTitle={`Tối đa ${MAX_REVIEW_IMAGES} ảnh`}
              overSuffix="để gửi đánh giá."
              hint={`Tối đa ${MAX_REVIEW_IMAGES} ảnh cho mỗi đánh giá.`}
              disabled={submit.isPending}
            />
            {over ? (
              <>
                <Button
                  title="Chọn lại ảnh"
                  onPress={async () => setFiles(await pickImages(MAX_REVIEW_IMAGES))}
                />
                <Button title="Quay lại" kind="secondary" onPress={() => setFiles([])} />
              </>
            ) : (
              <>
                <Button
                  testID="review-submit"
                  title="Gửi đánh giá"
                  loading={submit.isPending}
                  onPress={() => submit.mutate()}
                />
                <Button
                  testID="review-later"
                  title="Để sau"
                  kind="secondary"
                  disabled={submit.isPending}
                  onPress={backOr(`/orders/${orderId}`)}
                />
              </>
            )}
          </>
        )}
      </>
    )
  }

  return (
    <Screen testID="review-screen" title="Đánh giá sản phẩm" onBack={backOr(`/orders/${orderId}`)}>
      <AuthGate>{body()}</AuthGate>
    </Screen>
  )
}

function ThanksCard({
  done,
  label,
}: {
  done: { item: OrderItem; rating: number }
  label: (item: OrderItem) => { name: string; variant: string }
}) {
  const l = label(done.item)
  const name = l.variant ? `${l.name} / ${l.variant}` : l.name
  return (
    <InfoCard
      testID="review-thanks"
      title="Cảm ơn bạn đã đánh giá"
      body={`Đánh giá ${done.rating}★ cho ${name} đã được đăng. Mỗi sản phẩm chỉ đánh giá một lần.`}
    />
  )
}
