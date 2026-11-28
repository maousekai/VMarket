import { useLocalSearchParams } from 'expo-router'
import PaymentScreen from '../../../buyer/PaymentScreen'

export default function PaymentRoute() {
  const { orderId, queue } = useLocalSearchParams()
  const id = String(orderId || '')
  const list = String(queue || id)
    .split(',')
    .filter(Boolean)
  return <PaymentScreen key={id} orderId={id} queue={list.includes(id) ? list : [id]} />
}
