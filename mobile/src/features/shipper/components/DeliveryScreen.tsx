import type { ReactNode } from 'react'
import { View } from 'react-native'

import type { DeliveryDetail } from '@/api/delivery'
import { ErrorState, Screen, Skeleton } from '@/components/ui'

import { useDelivery } from '../hooks'

/**
 * Shipper screen shell for everything keyed by `orderId`: loads the delivery detail and renders
 * skeleton / error-with-retry / content. Header title is "VMarket Shipper" unless overridden.
 */
export function DeliveryScreen({
  orderId,
  title = 'VMarket Shipper',
  testID,
  children,
}: {
  orderId: string
  title?: string | ((detail?: DeliveryDetail) => string)
  testID?: string
  children: (detail: DeliveryDetail) => ReactNode
}) {
  const q = useDelivery(orderId)
  const heading = typeof title === 'function' ? title(q.data) : title
  return (
    <Screen
      title={heading}
      role="shipper"
      testID={testID}
      refreshing={q.isRefetching}
      onRefresh={() => void q.refetch()}
    >
      {q.isPending ? (
        <View style={{ gap: 12 }} testID="shipper-loading">
          <Skeleton height={31} width="70%" />
          <Skeleton height={21} width="50%" />
          <Skeleton height={110} radius={10} />
          <Skeleton height={110} radius={10} />
          <Skeleton height={48} radius={10} />
        </View>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        children(q.data)
      )}
    </Screen>
  )
}
