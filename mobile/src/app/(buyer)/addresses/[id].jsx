import { useLocalSearchParams } from 'expo-router'
import { AddressFormScreen } from '../../../buyer/AccountScreens'

export default function AddressFormRoute() {
  const { id } = useLocalSearchParams()
  return <AddressFormScreen key={String(id)} addressId={String(id || 'new')} />
}
