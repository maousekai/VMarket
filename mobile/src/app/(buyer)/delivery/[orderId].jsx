import { useLocalSearchParams } from 'expo-router'
import DeliveryScreen from '../../../buyer/DeliveryScreen'

export default function Route() {
  const params = useLocalSearchParams()
  const value = Array.isArray(params.orderId) ? params.orderId[0] : params.orderId
  return <DeliveryScreen orderId={value || ''} />
}
