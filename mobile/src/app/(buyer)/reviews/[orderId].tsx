import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'

import { getApiError } from '@/api/client'
import type { OrderItem } from '@/api/orders'
import {
  MAX_REVIEW_COMMENT,
  createReview,
  isAlreadyReviewed,
  isNotReviewable,
  isReviewDependencyDown,
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
import { RatingStars } from '@/features/orders/components/RatingStars'
import { orderKeys, useItemLabels, useOrder } from '@/features/orders/hooks'
import { backOr, orderPath, orderSubPath, reviewPath } from '@/features/orders/nav'
import { orderStatusInfo } from '@/features/orders/status'
import { itemKey, orderCode } from '@/features/orders/text'

const NOT_REVIEWABLE =
  'Đơn hàng này chưa thể đánh giá. Bạn chỉ đánh giá được sản phẩm trong đơn đã Hoàn thành của chính mình.'

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
    queryFn: () => listPendingReviews(order?.items ?? [], orderId),
    enabled: authed && order?.status === 'COMPLETED',
    retry: false,
  })

  const [rating, setRating] = useState(5)
  const [comment, setComment] = useState('')
  const [done, setDone] = useState<{ item: OrderItem; rating: number }[]>([])
  const [unavailable, setUnavailable] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const doneKeys = new Set(done.map((d) => itemKey(d.item)))
  const remaining = (pending.data ?? []).filter((i) => !doneKeys.has(itemKey(i)))
  const current = remaining.find((i) => itemKey(i) === itemId) ?? remaining[0]
  const justDone = done[done.length - 1]

  const submit = useMutation({
    mutationFn: async () => {
      if (!current) return
      await createReview({
        orderId,
        productId: current.productId,
        rating,
        comment: comment.trim() || undefined,
      })
    },
    onSuccess: () => {
      if (!current) return
      setDone((d) => [...d, { item: current, rating }])
      setRating(5)
      setComment('')
      setFormError(null)
      qc.invalidateQueries({ queryKey: ['reviews', 'pending', orderId] })
      qc.invalidateQueries({ queryKey: orderKeys.detail(orderId) })
    },
    onError: (e) => {
      if (isAlreadyReviewed(e)) {
        // Another device/session already reviewed it: refresh the pending list.
        qc.invalidateQueries({ queryKey: ['reviews', 'pending', orderId] })
        return setFormError('Sản phẩm này đã được đánh giá.')
      }
      if (isNotReviewable(e)) return setUnavailable(true)
      const err = getApiError(e)
      if (err.code === 'IMAGE_URL_NOT_ALLOWED') {
        return setFormError('Chưa hỗ trợ đính kèm ảnh.')
      }
      if (err.code === 'VALIDATION_ERROR') {
        return setFormError(
          err.fieldErrors.comment ?? err.fieldErrors.rating ?? 'Nội dung đánh giá chưa hợp lệ.',
        )
      }
      if (isReviewDependencyDown(e)) {
        return setFormError('Hệ thống đang bận, vui lòng thử lại sau ít phút.')
      }
      toast.show({ type: 'error', message: err.message })
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
    if (unavailable || (pending.isError && isNotReviewable(pending.error) && !justDone)) {
      return (
        <>
          <InfoCard title="Chưa thể đánh giá" body={NOT_REVIEWABLE} />
          {toOrder('primary')}
        </>
      )
    }
    if (pending.isPending) return <Skeleton height={130} />
    if (pending.isError)
      return <ErrorState error={pending.error} onRetry={() => pending.refetch()} />

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
            <AppText variant="label">Chấm điểm *</AppText>
            <RatingStars value={rating} onChange={setRating} disabled={submit.isPending} />
            <TextField
              label="Nhận xét"
              testID="review-comment"
              multiline
              maxLength={MAX_REVIEW_COMMENT}
              value={comment}
              onChangeText={setComment}
              placeholder="Chia sẻ cảm nhận của bạn"
              helper="Chưa hỗ trợ đính kèm ảnh."
              error={formError ?? undefined}
            />
            <Button
              testID="review-submit"
              title="Gửi đánh giá"
              loading={submit.isPending}
              onPress={() => {
                setFormError(null)
                submit.mutate()
              }}
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
