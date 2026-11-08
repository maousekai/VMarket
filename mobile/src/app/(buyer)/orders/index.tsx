import { router } from 'expo-router'
import { Fragment, useState } from 'react'
import { ScrollView, StyleSheet } from 'react-native'

import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  Button,
  Chip,
  EmptyState,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
} from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { backOr, orderPath } from '@/features/orders/nav'
import { useOrders } from '@/features/orders/hooks'
import { ORDER_FILTERS, orderStatusInfo, paymentLine } from '@/features/orders/status'
import { orderCode } from '@/features/orders/text'
import { formatVnd } from '@/lib/format'

const goBack = backOr('/(buyer)/account')

export default function OrdersScreen() {
  const { status } = useAuth()
  const [filterKey, setFilterKey] = useState('all')
  const q = useOrders(status === 'authenticated')
  const filter = ORDER_FILTERS.find((f) => f.key === filterKey) ?? ORDER_FILTERS[0]
  const orders = (q.data ?? []).filter(
    (o) => !filter.statuses || filter.statuses.includes(o.status),
  )

  return (
    <Screen
      testID="orders-screen"
      title="Đơn hàng của tôi"
      onBack={goBack}
      refreshing={q.isRefetching}
      onRefresh={status === 'authenticated' ? () => q.refetch() : undefined}
    >
      <AuthGate>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.chips}
          style={styles.chipScroll}
        >
          {ORDER_FILTERS.map((f) => (
            <Chip
              key={f.key}
              testID={`orders-filter-${f.key}`}
              label={f.label}
              selected={f.key === filterKey}
              onPress={() => setFilterKey(f.key)}
            />
          ))}
        </ScrollView>

        {q.isPending ? (
          <>
            <Skeleton height={109} />
            <Skeleton height={109} />
          </>
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => q.refetch()} />
        ) : orders.length === 0 ? (
          <EmptyState
            heading={filter.statuses ? 'Không có đơn' : 'Chưa có đơn hàng'}
            title={filter.statuses ? `Không có đơn "${filter.label}"` : 'Chưa có đơn hàng'}
            body={
              filter.statuses
                ? 'Chọn bộ lọc khác để xem các đơn còn lại.'
                : 'Các đơn bạn đặt sẽ hiển thị tại đây.'
            }
            primaryLabel={filter.statuses ? 'Xem tất cả đơn' : 'Về trang chủ'}
            onPrimary={() => (filter.statuses ? setFilterKey('all') : router.replace('/(buyer)'))}
          />
        ) : (
          orders.map((o, i) => {
            const open = () => router.push(orderPath(o.id))
            return (
              <Fragment key={o.id}>
                <InfoCard
                  testID={`order-card-${o.id}`}
                  title={orderCode(o.id)}
                  onPress={open}
                  body={`${formatVnd(o.totalAmount)} · ${paymentLine(o)}`}
                >
                  <AppText>{orderStatusInfo(o.status).label}</AppText>
                </InfoCard>
                <Button
                  testID={`order-open-${o.id}`}
                  title={`Xem đơn ${i + 1}`}
                  kind="secondary"
                  onPress={open}
                />
              </Fragment>
            )
          })
        )}
      </AuthGate>
    </Screen>
  )
}

const styles = StyleSheet.create({
  chipScroll: { flexGrow: 0, marginHorizontal: -20 },
  chips: { gap: 8, paddingHorizontal: 20 },
})
