import { router, type Href } from 'expo-router'
import { useMemo } from 'react'
import { View } from 'react-native'

import { AppText, Button, Card, ErrorState, Screen, Skeleton, StatusBadge } from '@/components/ui'
import { useAssignments } from '@/features/shipper/hooks'
import { orderCode, orderLine } from '@/features/shipper/lib/order'
import { isFinished, STATUS_LABEL, STATUS_TONE } from '@/features/shipper/lib/status'
import { formatDateTime } from '@/lib/format'

/** Tab "Lịch sử": finished deliveries (DELIVERED / RETURNED), newest first. */
export default function ShipperHistoryScreen() {
  const q = useAssignments()
  const finished = useMemo(
    () => q.assignments.filter(isFinished).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
    [q.assignments],
  )

  return (
    <Screen
      title="VMarket Shipper"
      role="shipper"
      testID="shipper-history"
      refreshing={q.isRefetching && !q.isFetchingNextPage}
      onRefresh={() => void q.refetch()}
    >
      <AppText variant="h2" accessibilityRole="header">
        Lịch sử giao đơn
      </AppText>
      {q.isPending ? (
        <View style={{ gap: 12 }} testID="shipper-loading">
          <Skeleton height={80} radius={10} />
          <Skeleton height={80} radius={10} />
        </View>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : finished.length === 0 ? (
        <AppText variant="body" testID="shipper-history-empty">
          Chưa có đơn nào đã hoàn tất.
        </AppText>
      ) : (
        <>
          {finished.map((a) => (
            <Card
              key={a.id}
              testID="shipper-history-item"
              onPress={() => router.push(`/(shipper)/history/${a.orderId}` as Href)}
            >
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
                <AppText variant="label" style={{ flex: 1 }} numberOfLines={1}>
                  {orderCode(a.orderId)}
                </AppText>
                <StatusBadge
                  compact
                  label={STATUS_LABEL[a.status] ?? a.status}
                  tone={STATUS_TONE[a.status] ?? 'neutral'}
                />
              </View>
              <AppText variant="body">{orderLine(a).split(' · ')[1]}</AppText>
              <AppText variant="caption" color="textSubtle">
                {formatDateTime(a.updatedAt)}
              </AppText>
            </Card>
          ))}
          {q.hasNextPage ? (
            <Button
              kind="secondary"
              title="Xem thêm"
              loading={q.isFetchingNextPage}
              onPress={() => void q.fetchNextPage()}
            />
          ) : null}
        </>
      )}
    </Screen>
  )
}
