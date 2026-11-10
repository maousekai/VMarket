import { useQuery } from '@tanstack/react-query'
import { router, useIsFocused, useLocalSearchParams } from 'expo-router'
import { Linking } from 'react-native'

import { getApiError } from '@/api/client'
import { getDelivery } from '@/api/tracking'
import { useAuth } from '@/auth/AuthProvider'
import { AppText, Button, ErrorState, InfoCard, Screen, Skeleton, useToast } from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { orderKeys, useOrder } from '@/features/orders/hooks'
import { backOr, goHome, orderPath } from '@/features/orders/nav'
import { deliveryStatusLabel } from '@/features/orders/status'
import { orderCode, shortDateTime } from '@/features/orders/text'
import {
  POLL_INTERVAL_MS,
  formatCoords,
  hasLocation,
  isFinalTracking,
  mapUrl,
  trackingState,
  useAppActive,
  useNow,
} from '@/features/orders/tracking'
import { formatTime } from '@/lib/format'

export default function TrackingScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { status: auth } = useAuth()
  const authed = auth === 'authenticated'
  const toast = useToast()
  const focused = useIsFocused()
  const appActive = useAppActive()
  const now = useNow(10_000)
  const order = useOrder(id, authed).data

  const tracking = useQuery({
    queryKey: [...orderKeys.detail(id), 'tracking'],
    enabled: authed,
    retry: false,
    staleTime: 0,
    queryFn: async () => {
      try {
        return await getDelivery(id)
      } catch (e) {
        if (getApiError(e).status === 404) return null
        throw e
      }
    },
    // Poll the shipper's last fix every ~15 s while the screen is focused and the app is active.
    refetchInterval: (query) => {
      const state = trackingState(query.state.data?.assignment, Date.now())
      return focused && appActive && !isFinalTracking(state) ? POLL_INTERVAL_MS : false
    },
  })

  const detail = tracking.data
  const assignment = detail?.assignment
  const state = trackingState(assignment, now, tracking.isError)
  const code = orderCode(id)
  const located = hasLocation(assignment)
  const fixTime = formatTime(assignment?.locationAt)

  const openMap = () => {
    if (!assignment || !located) return
    Linking.openURL(mapUrl(Number(assignment.latitude), Number(assignment.longitude))).catch(() =>
      toast.show({ type: 'error', message: 'Không mở được ứng dụng bản đồ trên thiết bị này.' }),
    )
  }

  const journey = (detail?.history ?? []).map(
    (h) => `${deliveryStatusLabel(h.status)} · ${shortDateTime(h.occurred_at) || '—'}`,
  )

  const title = state === 'stale' ? 'Vị trí chưa cập nhật' : 'Theo dõi giao hàng'

  return (
    <Screen
      testID="tracking-screen"
      title={title}
      onBack={backOr(`/orders/${id}`)}
      refreshing={tracking.isRefetching}
      onRefresh={authed ? () => tracking.refetch() : undefined}
    >
      <AuthGate>
        {tracking.isPending ? (
          <>
            <Skeleton height={88} />
            <Skeleton height={130} />
          </>
        ) : tracking.isError && !detail ? (
          <ErrorState
            error={tracking.error}
            onRetry={() => tracking.refetch()}
            secondaryLabel="Về trang chủ"
            onSecondary={goHome}
          />
        ) : (
          <>
            <AppText testID="tracking-summary">
              {`${code} · ${order?.status === 'DELIVERED' || state === 'delivered' ? 'Đã giao' : 'Đang giao'}`}
            </AppText>

            {state === 'waiting' ? (
              <InfoCard
                title="Chưa có vị trí shipper"
                body={
                  assignment
                    ? `${deliveryStatusLabel(assignment.status)}. Vị trí sẽ hiển thị khi shipper lấy hàng và bắt đầu giao.`
                    : 'Đơn chưa được bàn giao cho shipper. Vị trí sẽ hiển thị khi shipper lấy hàng.'
                }
              />
            ) : null}

            {state === 'delivered' || state === 'returned' ? (
              <InfoCard
                title={state === 'delivered' ? 'Đã giao' : 'Đã hoàn hàng'}
                body={
                  located
                    ? `Vị trí cuối ${fixTime ? `lúc ${fixTime}` : ''}: ${formatCoords(assignment!)}`
                    : 'Đơn hàng đã kết thúc hành trình giao.'
                }
              />
            ) : null}

            {state === 'live' && assignment ? (
              <>
                <InfoCard
                  title="Đang giao"
                  body={`Shipper đang trên đường giao hàng · cập nhật ${fixTime}`}
                />
                <InfoCard testID="tracking-location" title="Vị trí shipper">
                  <AppText>{formatCoords(assignment)}</AppText>
                  <AppText color="textSecondary">{`Cập nhật lúc ${fixTime}`}</AppText>
                  <AppText variant="caption" color="textSubtle">
                    Vị trí tự cập nhật khoảng 15 giây một lần khi bạn mở màn hình này.
                  </AppText>
                  <Button
                    testID="tracking-open-map"
                    title="Mở bản đồ"
                    kind="secondary"
                    onPress={openMap}
                  />
                </InfoCard>
              </>
            ) : null}

            {state === 'stale' ? (
              <InfoCard
                testID="tracking-stale"
                title={located ? `Vị trí cuối · ${fixTime}` : 'Vị trí chưa cập nhật'}
                body="Chưa nhận được vị trí mới từ shipper. Đơn hàng vẫn đang được giao bình thường; vị trí sẽ hiển thị lại khi có tín hiệu."
              >
                {located && assignment ? (
                  <>
                    <AppText>{formatCoords(assignment)}</AppText>
                    <Button title="Mở bản đồ" kind="secondary" onPress={openMap} />
                  </>
                ) : null}
              </InfoCard>
            ) : null}

            {journey.length ? (
              <InfoCard title="Hành trình">
                {journey.map((line) => (
                  <AppText key={line}>{line}</AppText>
                ))}
              </InfoCard>
            ) : null}

            {state === 'stale' ? (
              <>
                <Button
                  testID="tracking-retry"
                  title="Thử cập nhật lại"
                  loading={tracking.isFetching}
                  onPress={() => tracking.refetch()}
                />
                <Button title="Về trang chủ" kind="secondary" onPress={goHome} />
              </>
            ) : (
              <>
                {state === 'live' || state === 'waiting' ? (
                  <Button
                    testID="tracking-refresh"
                    title="Cập nhật vị trí"
                    kind="secondary"
                    loading={tracking.isFetching}
                    onPress={() => tracking.refetch()}
                  />
                ) : null}
                <Button
                  title="Xem đơn hàng"
                  kind={state === 'live' ? 'neutral' : 'primary'}
                  onPress={() => router.replace(orderPath(id))}
                />
              </>
            )}
          </>
        )}
      </AuthGate>
    </Screen>
  )
}
