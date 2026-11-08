import { Redirect, useLocalSearchParams } from 'expo-router'

import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { taskHref } from '@/features/shipper/actions'
import { AssignedView } from '@/features/shipper/views/AssignedView'
import { DeliveringView } from '@/features/shipper/views/DeliveringView'
import { DoneView } from '@/features/shipper/views/DoneView'
import { RedeliverView } from '@/features/shipper/views/RedeliverView'
import { ReturnView } from '@/features/shipper/views/ReturnView'

/**
 * One order, one screen: the body follows `Assignment.status`
 * (3.12 ASSIGNED, 3.14/3.23/3.25 IN_TRANSIT, 3.17 FAILED, 3.20 RETURNING, 3.19/3.29 finished).
 * Step screens (pickup, cod, failed, handover) sit above it and pop back here.
 */
export default function ShipperTaskScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  return (
    <DeliveryScreen orderId={orderId} testID="shipper-task">
      {(detail) => {
        switch (detail.assignment.status) {
          case 'ASSIGNED':
            return <AssignedView detail={detail} />
          case 'PICKED_UP':
            return <Redirect href={taskHref(orderId, 'pickup')} />
          case 'IN_TRANSIT':
            return <DeliveringView detail={detail} />
          case 'FAILED':
            return <RedeliverView detail={detail} />
          case 'RETURNING':
            return <ReturnView detail={detail} />
          default:
            return <DoneView detail={detail} />
        }
      }}
    </DeliveryScreen>
  )
}
