import { useQueryClient } from '@tanstack/react-query'
import { router, useLocalSearchParams, type Href } from 'expo-router'
import { useEffect, useState } from 'react'

import { getApiError } from '@/api/client'
import { getOrder } from '@/api/orders'
import { useAuth } from '@/auth/AuthProvider'
import { Button, InfoCard, Screen, useToast } from '@/components/ui'
import { AuthGate } from '@/features/orders/components/AuthGate'
import { orderKeys, useOrder, useWasPaid } from '@/features/orders/hooks'
import { goHome, orderPath, orderSubPath } from '@/features/orders/nav'
import { orderCode } from '@/features/orders/text'
import { formatVnd } from '@/lib/format'

type Outcome = 'cancelled' | 'confirmed' | 'unknown'

const TITLES: Record<Outcome, string> = {
  cancelled: 'Đã hủy đơn',
  confirmed: 'Đơn đã được xác nhận',
  unknown: 'Chưa hủy được đơn',
}

export default function CancelResultScreen() {
  const { id, outcome: raw } = useLocalSearchParams<{ id: string; outcome?: string }>()
  const outcome: Outcome = raw === 'cancelled' || raw === 'confirmed' ? raw : 'unknown'
  const { status: auth } = useAuth()
  const qc = useQueryClient()
  const toast = useToast()
  const [checking, setChecking] = useState(false)
  const q = useOrder(id, auth === 'authenticated')
  const order = q.data
  const paid = useWasPaid(order)
  const code = order ? orderCode(order.id) : orderCode(id)

  // The cancel attempt may have changed the order: always re-read it.
  useEffect(() => {
    qc.invalidateQueries({ queryKey: orderKeys.detail(id) })
  }, [qc, id])

  const recheck = async () => {
    setChecking(true)
    try {
      const fresh = await getOrder(id)
      qc.setQueryData(orderKeys.detail(id), fresh)
      qc.invalidateQueries({ queryKey: orderKeys.list })
      if (fresh.status === 'CANCELLED') {
        router.replace(`${orderSubPath(id, 'cancel-result')}?outcome=cancelled` as Href)
      } else {
        router.replace(orderPath(id))
      }
    } catch (e) {
      toast.show({ type: 'error', message: getApiError(e).message })
    } finally {
      setChecking(false)
    }
  }

  return (
    <Screen testID="cancel-result-screen" title={TITLES[outcome]} onBack={goHome}>
      <AuthGate>
        {outcome === 'cancelled' ? (
          <>
            <InfoCard
              title={`${code} · Đã hủy`}
              body="Yêu cầu hủy được xác nhận trước khi gian hàng xác nhận đơn."
            />
            {order && paid ? (
              <InfoCard
                testID="cancel-refund-card"
                title={`Chờ hoàn · ${formatVnd(order.totalAmount)}`}
                body="Đã thanh toán PayOS. Admin sẽ xử lý hoàn tiền thủ công; chưa có xác nhận đã hoàn."
              />
            ) : null}
            <Button title="Về trang chủ" onPress={goHome} />
          </>
        ) : null}

        {outcome === 'confirmed' ? (
          <>
            <InfoCard
              title="Không thể hủy đơn"
              body={`Gian hàng đã xác nhận đơn ${code} nên bạn không thể tự hủy. Đơn hàng vẫn được giữ; vui lòng liên hệ người bán nếu cần hỗ trợ.`}
            />
            <Button title="Về trang chủ" onPress={goHome} />
            <Button
              title="Xem đơn hàng"
              kind="secondary"
              onPress={() => router.replace(orderPath(id))}
            />
          </>
        ) : null}

        {outcome === 'unknown' ? (
          <>
            <InfoCard
              title="Chưa có kết quả xác nhận"
              body="Hệ thống chưa nhận được kết quả hủy đơn. Trạng thái đơn và khoản thanh toán chưa thay đổi; hãy kiểm tra lại đơn hàng trước khi thử hủy lần nữa."
            />
            <Button
              testID="cancel-recheck"
              title="Kiểm tra lại đơn hàng"
              loading={checking}
              onPress={recheck}
            />
          </>
        ) : null}
      </AuthGate>
    </Screen>
  )
}
