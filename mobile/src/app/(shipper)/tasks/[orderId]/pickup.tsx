import { useLocalSearchParams } from 'expo-router'

import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { PickupView } from '@/features/shipper/views/PickupView'

export default function ShipperPickupScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  return (
    <DeliveryScreen orderId={orderId} testID="shipper-pickup">
      {(detail) => <PickupView detail={detail} />}
    </DeliveryScreen>
  )
}
