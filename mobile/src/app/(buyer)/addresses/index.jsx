import { useLocalSearchParams } from 'expo-router'
import { AddressesScreen } from '../../../buyer/AccountScreens'

export default function AddressesRoute() {
  const { pick } = useLocalSearchParams()
  return <AddressesScreen pick={pick === '1'} />
}
