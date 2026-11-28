import { Linking } from 'react-native'
import { router } from 'expo-router'
import {
  Badge,
  Body,
  Button,
  Caption,
  Card,
  Label,
  Lines,
  Loading,
  Notice,
  Screen,
} from '../components/ui'
import { BUYER_DELIVERY_STATUS_LABELS, canBuyerTrack, displayDate, telUrl } from '../deliveryStatus'
import BuyerNav from './BuyerNav'
import { shortOrderId } from './catalog'
import DeliveryMap from './DeliveryMap'
import useBuyerDelivery from './useBuyerDelivery'

const STALE_MS = 2 * 60_000
const time = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false })

/**
 * Theo doi giao hang (frame 3.8; "Vi tri chua cap nhat" 3.39): trang thai, ban do realtime khi shipper
 * da lay hang, ten / SDT shipper, hanh trinh voi ly do cong khai.
 */
export default function DeliveryScreen({ orderId }) {
  const state = useBuyerDelivery(orderId || null)
  const { assignment, history } = state
  const phone = telUrl(assignment?.shipper?.phone)
  const showMap = state.authorized && canBuyerTrack(assignment?.status)
  const status = (value) => BUYER_DELIVERY_STATUS_LABELS[value] || 'Đang xử lý'
  const recordedAt = assignment?.location?.recordedAt
  const stale = showMap && (!recordedAt || Date.now() - Date.parse(recordedAt) > STALE_MS)

  return (
    <Screen
      title="Theo dõi đơn hàng"
      refreshing={state.loading && !!assignment}
      onRefresh={state.retry}
      footer={<BuyerNav active="home" />}
    >
      <Label>{shortOrderId(orderId)}</Label>
      {state.loading && !assignment ? <Loading /> : null}
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {!state.loading && !assignment && !state.error ? (
        <Card title="Chưa có chuyến giao">
          <Body>Gian hàng chưa bàn giao đơn cho đơn vị vận chuyển.</Body>
        </Card>
      ) : null}
      {assignment && (
        <>
          <Card title={status(assignment.status)}>
            {assignment.earliestRetryAt ? (
              <Body>{`Dự kiến giao lại từ ${displayDate(assignment.earliestRetryAt)}`}</Body>
            ) : null}
            {!showMap && !['DELIVERED', 'RETURNED'].includes(assignment.status) ? (
              <Body muted>Bản đồ hiển thị khi shipper đã lấy hàng và đang giao tới bạn.</Body>
            ) : null}
            <Badge
              label={state.live ? 'Trực tiếp' : 'Đang kết nối'}
              tone={state.live ? 'success' : 'info'}
            />
          </Card>
          {showMap && (
            <>
              <DeliveryMap
                location={assignment.location}
                deliveryPoint={assignment.deliveryPoint}
              />
              {stale ? (
                <Card title="Vị trí chưa cập nhật">
                  <Body>
                    {recordedAt
                      ? `Vị trí cuối lúc ${time.format(new Date(recordedAt))}. Shipper có thể đang mất sóng; bản đồ sẽ tự cập nhật khi có vị trí mới.`
                      : 'Đang chờ vị trí đầu tiên của shipper.'}
                  </Body>
                </Card>
              ) : (
                <Caption>{`Vị trí cập nhật lúc ${time.format(new Date(recordedAt))}`}</Caption>
              )}
            </>
          )}
          {assignment.shipper?.name ? (
            <Card title={`Shipper ${assignment.shipper.name}`}>
              {assignment.shipper.phone ? <Body>{assignment.shipper.phone}</Body> : null}
              {phone ? (
                <Button
                  title={`Gọi ${assignment.shipper.phone}`}
                  variant="secondary"
                  onPress={() => Linking.openURL(phone).catch(() => {})}
                />
              ) : null}
            </Card>
          ) : null}
          {history.length > 0 && (
            <Card title="Hành trình">
              <Lines
                lines={history.map((entry) =>
                  [
                    displayDate(entry.occurred_at),
                    status(entry.status),
                    entry.public_reason || null,
                  ]
                    .filter(Boolean)
                    .join(' · '),
                )}
              />
            </Card>
          )}
        </>
      )}
      <Button
        title="Xem chi tiết đơn"
        variant="secondary"
        onPress={() => router.push({ pathname: '/orders/[id]', params: { id: orderId } })}
      />
    </Screen>
  )
}
