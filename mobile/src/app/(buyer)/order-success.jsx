import { useLocalSearchParams } from 'expo-router'
import OrderSuccessScreen from '../../buyer/OrderSuccessScreen'

export default function OrderSuccessRoute() {
  const { ids } = useLocalSearchParams()
  return (
    <OrderSuccessScreen
      ids={String(ids || '')
        .split(',')
        .filter(Boolean)}
    />
  )
}
