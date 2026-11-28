import { useEffect, useState } from 'react'
import { Alert, Linking, Text, View } from 'react-native'
import * as Location from 'expo-location'
import { getApiError } from '../api/client'
import {
  confirmPickup,
  confirmReturn,
  formatMoney,
  isTerminalStatus,
  retryAssignment,
  serverNow,
  shipperActions,
  shortOrderId,
  updateAssignmentStatus,
} from '../api/deliveryApi'
import { lineName, useProductLookup } from '../buyer/catalog'
import { Badge, Body, Button, Card, Heading, Label, Lines, Notice } from '../components/ui'
import {
  DELIVERY_STATUS_LABELS,
  directionsUrl,
  displayDate,
  isReturnStatus,
  MAX_ATTEMPTS,
  telUrl,
} from '../deliveryStatus'
import { makeStyles, spacing } from '../theme'
import { DeliverSheet, FailSheet } from './ActionSheet'
import { openLocationSettings } from './TrackingBanner'

export const badgeTone = (status) =>
  status === 'DELIVERED'
    ? 'success'
    : ['FAILED', 'RETURNING', 'RETURN_DROPPED_OFF', 'RETURNED'].includes(status)
      ? 'danger'
      : 'info'

/** Tieu de man theo buoc giao (cac frame 3.12-3.20, 3.28-3.29 trong Figma). */
export function stepTitle(assignment) {
  switch (assignment.status) {
    case 'ASSIGNED':
      return (assignment.pickups || []).some((shop) => shop.picked_up_at)
        ? 'Lấy hàng'
        : 'Chi tiết đơn được phân công'
    case 'PICKED_UP':
    case 'IN_TRANSIT':
      return 'Đang giao'
    case 'FAILED':
      return (assignment.attempt || 1) > 1
        ? `Giao lại lần ${assignment.attempt - 1}`
        : 'Giao thất bại'
    case 'RETURNING':
      return 'Hoàn về gian hàng'
    case 'RETURN_DROPPED_OFF':
      return 'Đã bàn giao hoàn về'
    case 'DELIVERED':
      return 'Giao thành công'
    default:
      return 'Chi tiết đơn'
  }
}

/** "#VM… · COD 135.000đ" (chuyen hoan / tra truoc khong co COD). */
export function orderLine(assignment) {
  const returning = isReturnStatus(assignment.status)
  const cod = !returning && assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0
  return `${shortOrderId(assignment.orderId)} · ${cod ? `COD ${formatMoney(assignment.codAmount)}` : returning ? 'Hoàn hàng' : 'Đã thanh toán trước'}`
}

function Contact({ phone, address, location, onError }) {
  const styles = useStyles()
  const call = telUrl(phone)
  const directions = directionsUrl(location, address)
  if (!call && !directions) return null
  return (
    <View style={styles.row}>
      {call ? (
        <Button
          title={`Gọi ${phone}`}
          variant="secondary"
          style={styles.flex}
          onPress={() => Linking.openURL(call).catch(() => onError('Không mở được cuộc gọi.'))}
        />
      ) : null}
      {directions ? (
        <Button
          title="Chỉ đường"
          variant="secondary"
          style={styles.flex}
          onPress={() =>
            Linking.openURL(directions).catch(() => onError('Không mở được Google Maps.'))
          }
        />
      ) : null}
    </View>
  )
}

/** Diem lay / tra hang tung shop: dia chi, hang can lay (ten + so luong), lien he, xac nhan. */
function ShopStop({ assignment, shop, products, busy, onPickup, onReturn, onError }) {
  const returning = isReturnStatus(assignment.status)
  const items = shop.items || []
  const done = returning ? shop.returned_at : shop.picked_up_at
  return (
    <Card title={`${returning ? 'Trả hàng' : 'Lấy hàng'} · ${shop.name || 'Gian hàng'}`}>
      <Lines
        lines={[
          shop.address || null,
          ...items.map((item) => lineName(products, item.productId, item.variantId)),
          items.length
            ? `Số lượng: ${items.reduce((sum, item) => sum + Number(item.quantity || 0), 0)}`
            : null,
        ]}
      />
      <Contact
        phone={shop.phone}
        address={shop.address}
        location={shop.location}
        onError={onError}
      />
      {done ? (
        <Badge
          label={`${returning ? 'Đã trả hàng' : 'Đã lấy hàng'} · ${displayDate(done)}`}
          tone="success"
        />
      ) : assignment.status === 'RETURNING' ? (
        <Button title="Đã trả hàng ở shop này" disabled={busy} onPress={() => onReturn(shop)} />
      ) : assignment.status === 'ASSIGNED' ? (
        <Button title="Đã lấy hàng ở shop này" disabled={busy} onPress={() => onPickup(shop)} />
      ) : null}
    </Card>
  )
}

function RetryButton({ earliest, busy, onPress }) {
  const [now, setNow] = useState(serverNow)
  const remaining = Math.max(0, Math.ceil((Date.parse(earliest) - now) / 1000)) || 0
  const waiting = remaining > 0
  useEffect(() => {
    if (!waiting) return undefined
    const timer = setInterval(() => setNow(serverNow()), 1000)
    return () => clearInterval(timer)
  }, [waiting])
  const timeLeft = [
    Math.floor(remaining / 3600),
    Math.floor((remaining % 3600) / 60),
    remaining % 60,
  ]
    .map((unit) => String(unit).padStart(2, '0'))
    .join(':')
  return (
    <>
      {waiting && (
        <Body muted>
          Có thể giao lại sau {timeLeft} · {displayDate(earliest)}
        </Body>
      )}
      <Button title="Giao lại" disabled={waiting || busy} loading={busy} onPress={onPress} />
    </>
  )
}

/** Huong dan ngan duoi cac the, theo buoc (giong cac frame Figma). */
function stepHint(assignment) {
  switch (assignment.status) {
    case 'ASSIGNED':
      return 'Hệ thống đã phân công đơn này cho bạn theo khu vực. Đến từng gian hàng để lấy hàng; kiểm tra đúng mặt hàng, số lượng và tình trạng gói hàng.'
    case 'PICKED_UP':
    case 'IN_TRANSIT':
      return 'Đang chia sẻ vị trí khi giao hàng.'
    case 'RETURNING':
      return 'Mang hàng trả lại từng gian hàng; không thu tiền người mua.'
    case 'RETURN_DROPPED_OFF':
      return 'Đã trả hàng cho mọi gian hàng. Chờ gian hàng xác nhận đã nhận lại hàng.'
    default:
      return ''
  }
}

/**
 * Chi tiet mot chuyen va cac thao tac cua shipper (UC-23): lay hang tung shop, bat dau giao, giao
 * thanh cong (xac nhan COD), giao that bai, giao lai, tra hang tung shop.
 */
export default function AssignmentDetail({ assignment, onChanged, onStale }) {
  const styles = useStyles()
  const [sheet, setSheet] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [locationOff, setLocationOff] = useState(false)
  const returning = isReturnStatus(assignment.status)
  const cod = !returning && assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0
  const recipient = returning ? null : assignment.recipient
  const products = useProductLookup(
    (assignment.pickups || []).flatMap((shop) => (shop.items || []).map((item) => item.productId)),
  )
  const hint = stepHint(assignment)

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      onChanged(await action())
      setSheet('')
    } catch (failure) {
      const apiError = getApiError(failure)
      setError(apiError.message)
      if (['STALE_DELIVERY', 'RETRY_TOO_EARLY'].includes(apiError.code)) onStale()
    } finally {
      setBusy(false)
    }
  }

  const changeStatus = (status, extra = {}) =>
    run(() =>
      updateAssignmentStatus(assignment.id, {
        status,
        expectedVersion: assignment.version,
        ...extra,
      }),
    )

  async function pickUp(shop) {
    setBusy(true)
    setLocationOff(false)
    setError('')
    // Xin quyen vi tri TRUOC khi lay hang (frame 3.18): tu choi thi khong di tiep khi khong co GPS.
    try {
      const permission = await Location.requestForegroundPermissionsAsync().catch(() => null)
      if (permission?.status !== 'granted') {
        setError('Cần cấp quyền vị trí trước khi xác nhận lấy hàng để người mua theo dõi được đơn.')
        return
      }
      if (!(await Location.hasServicesEnabledAsync().catch(() => false))) {
        setLocationOff(true)
        setError('Cần bật định vị trước khi xác nhận lấy hàng.')
        return
      }
      await run(() => confirmPickup(assignment.id, shop.shop_id, assignment.version))
    } finally {
      setBusy(false)
    }
  }

  function confirmReturned(shop) {
    Alert.alert(
      'Xác nhận đã trả hàng?',
      `Xác nhận đã trả hàng về ${shop.name || 'gian hàng này'}.`,
      [
        { text: 'Huỷ', style: 'cancel' },
        {
          text: 'Đã trả hàng',
          onPress: () => run(() => confirmReturn(assignment.id, shop.shop_id, assignment.version)),
        },
      ],
    )
  }

  function onAction(action) {
    if (action.kind === 'deliver' || action.kind === 'fail') setSheet(action.kind)
    else if (action.kind === 'retry') run(() => retryAssignment(assignment.id, assignment.version))
    else changeStatus(action.status)
  }

  const attempts = `Lần giao ${assignment.attempt || 1}/${assignment.maxAttempts || MAX_ATTEMPTS}`
  const area = [assignment.ward, assignment.zone && `Khu ${assignment.zone}`]
    .filter(Boolean)
    .join(' · ')

  return (
    <View style={styles.stack}>
      <Heading>{stepTitle(assignment)}</Heading>
      <Label>{orderLine(assignment)}</Label>
      <View style={styles.meta}>
        <Badge
          label={DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
          tone={badgeTone(assignment.status)}
        />
        <Body muted>{[attempts, area].filter(Boolean).join(' · ')}</Body>
        {assignment.assignedAt ? (
          <Body muted>Phân công lúc {displayDate(assignment.assignedAt)}</Body>
        ) : null}
        {isTerminalStatus(assignment.status) && assignment.completedAt ? (
          <Body muted>Kết thúc lúc {displayDate(assignment.completedAt)}</Body>
        ) : null}
      </View>

      {(assignment.pickups || []).map((shop) => (
        <ShopStop
          key={shop.shop_id}
          assignment={assignment}
          shop={shop}
          products={products}
          busy={busy}
          onPickup={pickUp}
          onReturn={confirmReturned}
          onError={setError}
        />
      ))}
      {!assignment.pickups?.length && <Body muted>Chưa có thông tin điểm lấy hàng.</Body>}

      {returning ? (
        <Card title="Nhiệm vụ">
          <Body>Hoàn hàng về gian hàng — không thu tiền người mua.</Body>
        </Card>
      ) : recipient ? (
        <Card
          title={`${assignment.status === 'ASSIGNED' ? 'Giao cho' : 'Điểm giao ·'} ${recipient.name || 'người nhận'}`}
        >
          <Lines
            lines={[
              recipient.address || '—',
              recipient.phone || null,
              recipient.note ? `Ghi chú: ${recipient.note}` : null,
            ]}
          />
          <Contact {...recipient} onError={setError} />
        </Card>
      ) : null}

      {!returning && (
        <Card title={cod ? 'Cần thu COD' : 'Thu hộ'}>
          <Text style={cod ? styles.cod : styles.body}>
            {cod ? formatMoney(assignment.codAmount) : 'Đã thanh toán trước'}
          </Text>
        </Card>
      )}

      {assignment.failures?.some((entry) => entry.public_reason) && (
        <Card title="Các lần giao thất bại">
          <Lines
            lines={assignment.failures
              .filter((entry) => entry.public_reason)
              .map(
                (entry) =>
                  `Lần ${entry.attempt} · ${entry.public_reason} · ${displayDate(entry.occurred_at)}`,
              )}
          />
        </Card>
      )}

      {hint ? <Body>{hint}</Body> : null}
      {error && !sheet ? <Notice tone="danger">{error}</Notice> : null}
      {locationOff && <Button title="Bật định vị" onPress={openLocationSettings} />}
      {shipperActions(assignment).map((action) =>
        action.kind === 'retry' ? (
          <RetryButton
            key={assignment.earliestRetryAt || action.kind}
            earliest={assignment.earliestRetryAt}
            busy={busy}
            onPress={() => onAction(action)}
          />
        ) : (
          <Button
            key={action.label}
            title={action.label}
            variant={action.danger ? 'secondary' : 'primary'}
            loading={busy && !sheet}
            disabled={busy}
            onPress={() => onAction(action)}
          />
        ),
      )}
      {sheet === 'deliver' && (
        <DeliverSheet
          assignment={assignment}
          busy={busy}
          error={error}
          onClose={() => setSheet('')}
          onSubmit={(extra) => changeStatus('DELIVERED', extra)}
        />
      )}
      {sheet === 'fail' && (
        <FailSheet
          assignment={assignment}
          busy={busy}
          error={error}
          onClose={() => setSheet('')}
          onSubmit={(extra) => changeStatus('FAILED', extra)}
        />
      )}
    </View>
  )
}

const useStyles = makeStyles((colors) => ({
  stack: { gap: spacing(3) },
  meta: { gap: spacing(1) },
  row: { flexDirection: 'row', gap: spacing(2) },
  flex: { flex: 1 },
  body: { color: colors.text, fontSize: 14 },
  cod: { color: colors.primaryText, fontSize: 20, fontWeight: '600' },
}))
