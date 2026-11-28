import { useCallback, useEffect, useRef, useState } from 'react'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { formatMoney, listMyAssignments, shortOrderId } from '../api/deliveryApi'
import { Body, Button, Card, Heading, Lines, Loading, Notice, Screen } from '../components/ui'
import { DELIVERY_STATUS_LABELS, displayDate } from '../deliveryStatus'

const PAGE_SIZE = 20
const time = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false })
const day = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit' })
const at = (value) => (value ? `${time.format(new Date(value))}` : '')

/** Hanh trinh mot don da ket thuc (frame 3.27): moc nhan phan cong, that bai, ket thuc, COD. */
function journey(assignment) {
  const lines = []
  if (assignment.assignedAt)
    lines.push(
      `${day.format(new Date(assignment.assignedAt))} · ${at(assignment.assignedAt)} Nhận phân công`,
    )
  for (const shop of assignment.pickups || []) {
    if (shop.picked_up_at)
      lines.push(`${at(shop.picked_up_at)} Đã lấy hàng · ${shop.name || 'shop'}`)
    if (shop.returned_at) lines.push(`${at(shop.returned_at)} Đã trả hàng · ${shop.name || 'shop'}`)
  }
  for (const failure of assignment.failures || [])
    lines.push(`${at(failure.occurred_at)} Giao thất bại · ${failure.public_reason || ''}`)
  if (assignment.completedAt)
    lines.push(
      `${at(assignment.completedAt)} ${DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}`,
    )
  if (assignment.status === 'DELIVERED' && Number(assignment.codAmount) > 0)
    lines.push(`Đã thu COD: ${formatMoney(assignment.codAmount)}`)
  if (assignment.status === 'DELIVERED') lines.push('Chia sẻ vị trí đã dừng.')
  return lines
}

/** Tab "Lich su": chuyen da ket thuc, phan trang (scope=finished). */
export default function HistoryScreen() {
  const [state, setState] = useState({ items: [], page: 0, more: false, loading: true, error: '' })
  const request = useRef(null)

  const load = useCallback(async (page = 0) => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const items = await listMyAssignments({
        scope: 'finished',
        page,
        size: PAGE_SIZE,
        signal: controller.signal,
      })
      if (controller.signal.aborted) return
      setState((previous) => ({
        items: [
          ...new Map(
            [...(page ? previous.items : []), ...items].map((item) => [item.id, item]),
          ).values(),
        ],
        page,
        more: items.length === PAGE_SIZE,
        loading: false,
        error: '',
      }))
    } catch (failure) {
      if (controller.signal.aborted) return
      setState((previous) => ({
        ...previous,
        loading: false,
        error: getApiError(failure).message,
      }))
    }
  }, [])

  useEffect(() => {
    load()
    return () => request.current?.abort()
  }, [load])

  return (
    <Screen
      title="VMarket Shipper"
      role="shipper"
      refreshing={state.loading && !state.items.length}
      onRefresh={() => load()}
    >
      <Heading>Lịch sử giao đơn</Heading>
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {!state.loading && !state.items.length && !state.error && (
        <Body muted>Chưa có chuyến nào kết thúc.</Body>
      )}
      {state.items.map((assignment) => (
        <Card
          key={assignment.id}
          title={`${shortOrderId(assignment.orderId)} · ${DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}`}
          onPress={() =>
            router.push({ pathname: '/assignment/[id]', params: { id: assignment.id } })
          }
          accessibilityLabel={`Xem đơn ${shortOrderId(assignment.orderId)}`}
        >
          <Lines lines={journey(assignment)} />
          {assignment.completedAt ? (
            <Body muted>Kết thúc {displayDate(assignment.completedAt)}</Body>
          ) : null}
        </Card>
      ))}
      {state.loading && <Loading />}
      {state.more && !state.loading && (
        <Button title="Tải thêm" variant="secondary" onPress={() => load(state.page + 1)} />
      )}
    </Screen>
  )
}
