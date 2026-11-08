import { useLocalSearchParams } from 'expo-router'

import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { isLivePending } from '@/features/shipper/lib/pending'
import { usePendingWrite } from '@/features/shipper/lib/usePending'
import { CodView } from '@/features/shipper/views/CodView'

export default function ShipperCodScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  const { pending, save, clear } = usePendingWrite('cod', orderId)
  return (
    <DeliveryScreen
      orderId={orderId}
      testID="shipper-cod"
      title={(d) =>
        isLivePending(pending, d?.assignment) ? 'Chưa lưu được COD' : 'VMarket Shipper'
      }
    >
      {(detail) =>
        pending === undefined ? null : (
          <CodView detail={detail} pending={pending} savePending={save} clearPending={clear} />
        )
      }
    </DeliveryScreen>
  )
}
