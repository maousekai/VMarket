import { useLocalSearchParams } from 'expo-router'
import SearchScreen from '../../buyer/SearchScreen'

export default function Route() {
  const params = useLocalSearchParams()
  const value = Array.isArray(params.q) ? params.q[0] : params.q
  return <SearchScreen initialKeyword={value || ''} />
}
