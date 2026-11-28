import { useLocalSearchParams } from 'expo-router'
import AssignmentDetailScreen from '../../../shipper/AssignmentDetailScreen'

export default function AssignmentRoute() {
  const { id } = useLocalSearchParams()
  return <AssignmentDetailScreen id={typeof id === 'string' ? id : ''} />
}
