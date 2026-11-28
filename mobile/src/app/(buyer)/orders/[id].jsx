import { useLocalSearchParams } from 'expo-router'
import OrderDetailScreen from '../../../buyer/OrderDetailScreen'

export default function Route() {
  const params = useLocalSearchParams()
  const value = Array.isArray(params.id) ? params.id[0] : params.id
  return <OrderDetailScreen orderId={value || ''} />
}
