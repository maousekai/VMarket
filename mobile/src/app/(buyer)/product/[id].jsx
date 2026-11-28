import { useLocalSearchParams } from 'expo-router'
import ProductScreen from '../../../buyer/ProductScreen'

export default function Route() {
  const params = useLocalSearchParams()
  const value = Array.isArray(params.id) ? params.id[0] : params.id
  return <ProductScreen productId={value || ''} />
}
