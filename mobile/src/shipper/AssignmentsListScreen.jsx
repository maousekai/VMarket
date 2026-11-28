import { useState } from 'react'
import { router } from 'expo-router'
import {
  Badge,
  Body,
  Button,
  Card,
  Heading,
  Label,
  Lines,
  Loading,
  Notice,
  Screen,
} from '../components/ui'
import { DELIVERY_STATUS_LABELS, isReturnStatus } from '../deliveryStatus'
import { badgeTone, orderLine } from './AssignmentDetail'
import ShiftBar from './ShiftBar'
import { useShipper } from './ShipperProvider'
import TrackingBanner from './TrackingBanner'

/** Mot chuyen trong danh sach (frame 3.11): diem lay hang, nguoi nhan, khu, nut xem chi tiet. */
function AssignmentSummary({ assignment }) {
  const returning = isReturnStatus(assignment.status)
  const shops = assignment.pickups || []
  const recipient = returning ? null : assignment.recipient
  return (
    <>
      <Label>{orderLine(assignment)}</Label>
      <Badge
        label={DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
        tone={badgeTone(assignment.status)}
      />
      {shops.map((shop) => (
        <Card
          key={shop.shop_id}
          title={`${returning ? 'Trả hàng' : 'Lấy hàng'} · ${shop.name || 'Gian hàng'}`}
        >
          <Lines lines={[shop.address || null]} />
        </Card>
      ))}
      {recipient && (
        <Card title={`Giao cho ${recipient.name || 'người nhận'}`}>
          <Lines lines={[recipient.address || null, recipient.phone || null]} />
        </Card>
      )}
      {assignment.zone || assignment.ward ? (
        <Body muted>
          {[assignment.ward, assignment.zone && `khu vực ${assignment.zone}`]
            .filter(Boolean)
            .join(' · ')}
        </Body>
      ) : null}
      <Button
        title="Xem chi tiết đơn"
        onPress={() => router.push({ pathname: '/assignment/[id]', params: { id: assignment.id } })}
      />
    </>
  )
}

/** Tab "Don giao" cua shipper (UC-23): ca lam viec, trang thai GPS, chuyen dang xu ly. */
export default function AssignmentsListScreen() {
  const { status, items, error, load, revision, tracking } = useShipper()
  const [refreshing, setRefreshing] = useState(false)

  async function refresh() {
    setRefreshing(true)
    await load()
    setRefreshing(false)
  }

  return (
    <Screen title="VMarket Shipper" role="shipper" refreshing={refreshing} onRefresh={refresh}>
      <Heading>Đơn được phân công</Heading>
      <ShiftBar revision={revision} onChanged={load} />
      <TrackingBanner tracking={tracking} />
      <Button title="Thông báo" variant="secondary" onPress={() => router.push('/notifications')} />
      {status === 'loading' && <Loading />}
      {status === 'error' && (
        <>
          <Notice tone="danger">{error}</Notice>
          <Button title="Thử lại" onPress={refresh} />
        </>
      )}
      {status !== 'loading' && !items.length && status !== 'error' && (
        <Card title="Chưa có chuyến nào cần giao">
          <Body>Bắt đầu ca để hệ thống tự phân công đơn trong khu vực của bạn.</Body>
        </Card>
      )}
      {items.map((assignment) => (
        <AssignmentSummary key={assignment.id} assignment={assignment} />
      ))}
    </Screen>
  )
}
