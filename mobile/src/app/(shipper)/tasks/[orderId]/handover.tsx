import { useLocalSearchParams } from 'expo-router'

import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { HandoverView } from '@/features/shipper/views/HandoverView'

export default function ShipperHandoverScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  return (
    <DeliveryScreen orderId={orderId} testID="shipper-handover">
      {(detail) => <HandoverView detail={detail} />}
    </DeliveryScreen>
  )
}
