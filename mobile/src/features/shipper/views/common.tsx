import { useMemo } from 'react'

import type { DeliveryDetail } from '@/api/delivery'
import { AppText } from '@/components/ui'
import { formatPhone } from '@/lib/format'

import { useItemLabels } from '../hooks'
import { parseOrder, parsePickup, type Receiver } from '../lib/order'

/** Parsed view-model shared by all status views. */
export function useTaskData(detail: DeliveryDetail) {
  const order = useMemo(() => parseOrder(detail.order), [detail.order])
  const shops = useMemo(() => detail.pickups.map(parsePickup), [detail.pickups])
  const labels = useItemLabels(order.items)
  return { a: detail.assignment, order, shops, labels, history: detail.history }
}

export type TaskData = ReturnType<typeof useTaskData>

export const ScreenTitle = ({ children }: { children: string }) => (
  <AppText variant="h2" accessibilityRole="header">
    {children}
  </AppText>
)

export const contactLine = (r: Receiver) =>
  [r.name, r.phone ? formatPhone(r.phone) : ''].filter(Boolean).join(' · ')
