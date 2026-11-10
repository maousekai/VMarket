import { useLocalSearchParams } from 'expo-router'

import { DeliveryScreen } from '@/features/shipper/components/DeliveryScreen'
import { isLivePending } from '@/features/shipper/lib/pending'
import { usePendingWrite } from '@/features/shipper/lib/usePending'
import { FailedView } from '@/features/shipper/views/FailedView'

export default function ShipperFailedScreen() {
  const { orderId } = useLocalSearchParams<{ orderId: string }>()
  const { pending, save, clear } = usePendingWrite('failed', orderId)
  return (
    <DeliveryScreen
      orderId={orderId}
      testID="shipper-failed"
      title={(d) =>
        isLivePending(pending, d?.assignment) ? 'Chưa lưu được giao thất bại' : 'VMarket Shipper'
      }
    >
      {(detail) =>
        pending === undefined ? null : (
          <FailedView detail={detail} pending={pending} savePending={save} clearPending={clear} />
        )
      }
    </DeliveryScreen>
  )
}
