import { useMemo } from 'react'
import { View } from 'react-native'

import { AppText, Divider, ErrorState, Screen, Skeleton } from '@/components/ui'
import { AssignmentCard } from '@/features/shipper/components/AssignmentCard'
import { GpsBanner } from '@/features/shipper/components/GpsBanner'
import { ShipperLogout } from '@/features/shipper/components/LogoutButton'
import { NotificationsEntry } from '@/features/shipper/components/NotificationsEntry'
import { useAssignments } from '@/features/shipper/hooks'
import { parseOrder } from '@/features/shipper/lib/order'
import { isTracking, sortActive } from '@/features/shipper/lib/status'
import { useLocationSharing } from '@/features/shipper/location/useLocation'

/**
 * Frame 3.11: assigned orders (tab "Đơn giao"). This root screen also owns the GPS sharing
 * lifecycle for the whole shipper area (see location/tracker.ts).
 */
export default function ShipperTasksScreen() {
  const q = useAssignments()
  useLocationSharing(q.assignments, q.complete)
  const active = useMemo(() => sortActive(q.assignments), [q.assignments])
  const waiting = active.filter((a) => a.status === 'ASSIGNED')
  const district = waiting.length ? parseOrder(waiting[0].orderSnapshot).receiver.district : ''

  return (
    <Screen
      title="VMarket Shipper"
      role="shipper"
      testID="shipper-tasks"
      refreshing={q.isRefetching && !q.isFetchingNextPage}
      onRefresh={() => void q.refetch()}
    >
      <AppText variant="h2" accessibilityRole="header">
        Đơn được phân công
      </AppText>
      <NotificationsEntry />
      <GpsBanner />
      {q.isPending ? (
        <View style={{ gap: 12 }} testID="shipper-loading">
          <Skeleton height={21} width="60%" />
          <Skeleton height={96} radius={10} />
          <Skeleton height={48} radius={10} />
        </View>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : active.length === 0 ? (
        <AppText variant="body" testID="shipper-empty">
          Chưa có đơn được phân công
        </AppText>
      ) : (
        <>
          {waiting.length > 0 ? (
            <AppText variant="body">
              {`${waiting.length} đơn được phân công${district ? ` theo khu vực ${district}` : ''} · chờ lấy hàng`}
            </AppText>
          ) : null}
          {active.map((a, i) => (
            <View key={a.id} style={{ gap: 12 }}>
              {i > 0 ? <Divider /> : null}
              <AssignmentCard assignment={a} />
            </View>
          ))}
        </>
      )}
      <ShipperLogout hasActiveDelivery={q.assignments.some(isTracking)} />
    </Screen>
  )
}
