import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Alert,
  AppState,
  FlatList,
  Linking,
  Platform,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import * as Location from 'expo-location'
import { SafeAreaView } from 'react-native-safe-area-context'
import { getApiError } from '../api/client'
import {
  confirmPickup,
  confirmReturn,
  formatMoney,
  isTerminalStatus,
  listMyAssignments,
  serverNow,
  retryAssignment,
  shipperActions,
  shortOrderId,
  updateAssignmentStatus,
} from '../api/deliveryApi'
import { Badge, Button, Card, Loading, Notice } from '../components/ui'
import {
  DELIVERY_STATUS_LABELS,
  directionsUrl,
  displayDate,
  isReturnStatus,
  MAX_ATTEMPTS,
  telUrl,
} from '../deliveryStatus'
import { colors, radius, spacing } from '../theme'
import { DeliverSheet, FailSheet } from './ActionSheet'
import { stopTracking } from './locationTracking'
import useShipperTracking from './useShipperTracking'

const TABS = [
  ['active', 'Đang xử lý'],
  ['done', 'Đã kết thúc'],
]
const FINISHED_PAGE_SIZE = 20

const badgeTone = (status) =>
  status === 'DELIVERED'
    ? 'success'
    : ['FAILED', 'RETURNING', 'RETURNED'].includes(status)
      ? 'danger'
      : 'info'

const timeFormat = new Intl.DateTimeFormat('vi-VN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})
const openLocationSettings = () =>
  Platform.OS === 'android'
    ? Linking.sendIntent('android.settings.LOCATION_SOURCE_SETTINGS').catch(() =>
        Linking.openSettings(),
      )
    : Linking.openSettings()

function TrackingBanner({ tracking }) {
  if (!tracking.trackingCount) return null
  if (tracking.mode === 'servicesOff')
    return (
      <View style={styles.banner}>
        <Notice tone="danger">
          Định vị của máy đang tắt nên không gửi được vị trí. Bật định vị (GPS) rồi quay lại app.
        </Notice>
        <View style={styles.row}>
          <Button title="Bật định vị" onPress={openLocationSettings} style={styles.flex} />
          <Button
            title="Thử lại"
            variant="secondary"
            onPress={tracking.retry}
            style={styles.flex}
          />
        </View>
      </View>
    )
  if (tracking.mode === 'revoked')
    return (
      <View style={styles.banner}>
        <Notice tone="danger">
          {`Đã dừng chia sẻ vị trí: ${tracking.error || 'bạn không còn quyền với chuyến này.'}`}
        </Notice>
      </View>
    )
  if (tracking.mode === 'error')
    return (
      <View style={styles.banner}>
        <Notice tone="danger">{`Không lấy được vị trí: ${tracking.error}`}</Notice>
        <Button title="Thử lại" onPress={tracking.retry} />
      </View>
    )
  if (tracking.mode === 'denied')
    return (
      <View style={styles.banner}>
        <Notice tone="danger">
          Chưa có quyền vị trí. Người mua không thấy được hành trình — hãy cấp quyền vị trí cho
          VMarket.
        </Notice>
        <View style={styles.row}>
          <Button title="Thử lại" onPress={tracking.retry} style={styles.flex} />
          <Button
            title="Mở cài đặt"
            variant="secondary"
            onPress={() => Linking.openSettings()}
            style={styles.flex}
          />
        </View>
      </View>
    )
  const sent = tracking.lastSentAt ? ` · gửi lúc ${timeFormat.format(tracking.lastSentAt)}` : ''
  // Chi bao "dang chia se" sau khi server da nhan diem that (khong chi vi da co quyen).
  const message = !tracking.sharing
    ? 'Đang chờ tín hiệu GPS, chưa gửi được vị trí…'
    : tracking.mode === 'background'
      ? `Đang chia sẻ vị trí cho ${tracking.trackingCount} chuyến, kể cả khi app chạy nền${sent}.`
      : `Đang chia sẻ vị trí cho ${tracking.trackingCount} chuyến khi app đang mở${sent}.`
  return (
    <View style={styles.banner}>
      <Notice tone={tracking.error || !tracking.sharing ? 'warning' : 'success'}>
        {message}
        {tracking.error ? `\n${tracking.error}` : ''}
      </Notice>
      {tracking.mode === 'foreground' && tracking.backgroundAvailable && (
        <Button
          title="Bật chia sẻ khi chạy nền"
          variant="secondary"
          onPress={tracking.enableBackground}
        />
      )}
      {tracking.mode === 'foreground' && tracking.backgroundDenied && (
        <>
          <Notice tone="warning">
            Chưa bật được quyền vị trí khi chạy nền. Chọn Luôn cho phép trong cài đặt của VMarket.
          </Notice>
          <Button title="Mở cài đặt" variant="secondary" onPress={() => Linking.openSettings()} />
        </>
      )}
    </View>
  )
}

function Contact({ phone, address, location, onError }) {
  const call = telUrl(phone)
  const directions = directionsUrl(location, address)
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

function ShopStops({ assignment, busy, onPickup, onReturn, onError }) {
  const returning = isReturnStatus(assignment.status)
  if (assignment.status !== 'ASSIGNED' && !returning) return null
  return (
    <View style={styles.stops}>
      <Text style={styles.label}>{returning ? 'Điểm trả hàng' : 'Điểm lấy hàng'}</Text>
      {assignment.pickups?.map((shop) => (
        <View key={shop.shop_id} style={styles.stop}>
          <Text style={styles.orderId}>{shop.name || 'Gian hàng'}</Text>
          {shop.address ? <Text style={styles.value}>{shop.address}</Text> : null}
          <Contact
            phone={shop.phone}
            address={shop.address}
            location={shop.location}
            onError={onError}
          />
          {returning ? (
            shop.returned_at ? (
              <Text style={styles.muted}>Đã trả hàng · {displayDate(shop.returned_at)}</Text>
            ) : (
              assignment.status === 'RETURNING' && (
                <Button
                  title="Đã trả hàng ở shop này"
                  disabled={busy}
                  onPress={() => onReturn(shop)}
                />
              )
            )
          ) : shop.picked_up_at ? (
            <Text style={styles.muted}>Đã lấy hàng · {displayDate(shop.picked_up_at)}</Text>
          ) : (
            <Button title="Đã lấy hàng ở shop này" disabled={busy} onPress={() => onPickup(shop)} />
          )}
        </View>
      ))}
      {!assignment.pickups?.length && (
        <Text style={styles.muted}>Chưa có thông tin điểm dừng.</Text>
      )}
    </View>
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
        <Text style={styles.muted}>
          Có thể giao lại sau {timeLeft} · {displayDate(earliest)}
        </Text>
      )}
      <Button title="Giao lại" disabled={waiting || busy} loading={busy} onPress={onPress} />
    </>
  )
}

function AssignmentCard({ assignment, onChanged, onStale }) {
  const [sheet, setSheet] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [locationOff, setLocationOff] = useState(false)
  const returning = isReturnStatus(assignment.status)
  const cod = !returning && assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0
  const recipient = returning ? null : assignment.recipient
  const assignedAt = displayDate(assignment.assignedAt)
  const completedAt = isTerminalStatus(assignment.status) ? displayDate(assignment.completedAt) : ''

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
    // Xin quyen vi tri TRUOC khi lay hang: tu choi thi khong de chuyen di tiep ma khong co GPS.
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

  return (
    <Card>
      <View style={styles.cardHead}>
        <View style={styles.flex}>
          <Text style={styles.orderId}>Đơn {shortOrderId(assignment.orderId)}</Text>
          <Text style={styles.muted}>
            Lần giao {assignment.attempt || 1}/{assignment.maxAttempts || MAX_ATTEMPTS}
          </Text>
          {assignment.ward || assignment.zone ? (
            <Text style={styles.muted}>
              {[assignment.ward, assignment.zone && `Khu ${assignment.zone}`]
                .filter(Boolean)
                .join(' · ')}
            </Text>
          ) : null}
          {assignedAt ? <Text style={styles.muted}>Phân công lúc {assignedAt}</Text> : null}
          {completedAt ? <Text style={styles.muted}>Kết thúc lúc {completedAt}</Text> : null}
        </View>
        <Badge
          label={DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
          tone={badgeTone(assignment.status)}
        />
      </View>
      {returning ? (
        <View style={styles.info}>
          <Text style={styles.label}>Nhiệm vụ</Text>
          <Text style={styles.value}>Hoàn hàng về shop — không thu tiền người mua.</Text>
        </View>
      ) : recipient ? (
        <View style={styles.info}>
          <Text style={styles.label}>Người nhận</Text>
          <Text style={styles.value}>{recipient.name || '—'}</Text>
          <Text style={styles.label}>Địa chỉ</Text>
          <Text style={styles.value}>{recipient.address || '—'}</Text>
          <Contact {...recipient} onError={setError} />
          {recipient.note ? (
            <>
              <Text style={styles.label}>Ghi chú</Text>
              <Text style={styles.value}>{recipient.note}</Text>
            </>
          ) : null}
          <Text style={styles.label}>Thu hộ</Text>
          <Text style={[styles.value, cod && styles.cod]}>
            {cod ? `COD ${formatMoney(assignment.codAmount)}` : 'Đã thanh toán trước'}
          </Text>
        </View>
      ) : null}
      <ShopStops
        assignment={assignment}
        busy={busy}
        onPickup={pickUp}
        onReturn={confirmReturned}
        onError={setError}
      />
      {assignment.failures?.some((entry) => entry.public_reason) && (
        <View style={styles.info}>
          <Text style={styles.label}>Các lần giao thất bại</Text>
          {assignment.failures
            .filter((entry) => entry.public_reason)
            .map((entry) => (
              <Text key={`${entry.attempt}-${entry.occurred_at}`} style={styles.value}>
                Lần {entry.attempt} · {entry.public_reason} · {displayDate(entry.occurred_at)}
              </Text>
            ))}
        </View>
      )}
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
            variant={action.danger ? 'danger' : 'primary'}
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
    </Card>
  )
}

/** Phan he shipper (UC-23, FR-SHIP-03/04): don duoc phan cong, cap nhat trang thai, gui GPS. */
export default function AssignmentsScreen() {
  const [tab, setTab] = useState('active')
  const [state, setState] = useState({ status: 'loading', items: [], error: '', revision: 0 })
  const [refreshing, setRefreshing] = useState(false)
  const [finished, setFinished] = useState({
    items: [],
    page: 0,
    more: false,
    loading: false,
    error: '',
  })
  const request = useRef(0)
  const finishedRequest = useRef(null)

  const load = useCallback(async () => {
    const current = ++request.current
    try {
      const items = await listMyAssignments({ scope: 'active' })
      if (current !== request.current) return
      setState((previous) => ({
        status: 'ready',
        items,
        error: '',
        revision: previous.revision + 1,
      }))
    } catch (failure) {
      if (current !== request.current) return
      const error = getApiError(failure)
      if (error.status === 403) {
        finishedRequest.current?.abort()
        setFinished({ items: [], page: 0, more: false, loading: false, error: '' })
        await stopTracking().catch(() => {})
      }
      if (current !== request.current) return
      setState((previous) => ({
        ...previous,
        items: error.status === 403 ? [] : previous.items,
        status: 'error',
        error: error.message,
        revision: error.status === 403 ? 0 : previous.revision,
      }))
    }
  }, [])

  useEffect(() => {
    load()
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') load()
    })
    return () => {
      subscription.remove()
      request.current += 1
      finishedRequest.current?.abort()
    }
  }, [load])

  const tracking = useShipperTracking(state.items, state.revision, load)

  const onChanged = (updated) => {
    request.current += 1
    setState((previous) => ({
      ...previous,
      items: isTerminalStatus(updated.status)
        ? previous.items.filter((item) => item.id !== updated.id)
        : previous.items.map((item) => (item.id === updated.id ? updated : item)),
    }))
  }

  const loadFinished = async (page = 0) => {
    finishedRequest.current?.abort()
    const controller = new AbortController()
    finishedRequest.current = controller
    setFinished((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const items = await listMyAssignments({
        scope: 'finished',
        page,
        size: FINISHED_PAGE_SIZE,
        signal: controller.signal,
      })
      if (controller.signal.aborted) return
      setFinished((previous) => ({
        items: [
          ...new Map(
            [...(page ? previous.items : []), ...items].map((item) => [item.id, item]),
          ).values(),
        ],
        page,
        more: items.length === FINISHED_PAGE_SIZE,
        loading: false,
        error: '',
      }))
    } catch (failure) {
      if (controller.signal.aborted) return
      const error = getApiError(failure)
      setFinished((previous) => ({
        ...previous,
        items: error.status === 403 ? [] : previous.items,
        loading: false,
        error: error.message,
      }))
      if (error.status === 403) load()
    }
  }

  async function refresh() {
    setRefreshing(true)
    await Promise.all([load(), tab === 'done' && loadFinished()])
    setRefreshing(false)
  }

  const showingFinished = tab === 'done'

  if (state.status === 'loading') return <Loading />

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header">
          Đơn được phân công
        </Text>
        <View style={styles.tabs} accessibilityRole="tablist">
          {TABS.map(([value, label]) => (
            <Pressable
              key={value}
              accessibilityRole="tab"
              accessibilityState={{ selected: tab === value }}
              onPress={() => {
                setTab(value)
                if (value === 'done' && tab !== value) loadFinished()
              }}
              style={[styles.tab, tab === value && styles.tabActive]}
            >
              <Text style={[styles.tabText, tab === value && styles.tabTextActive]}>{label}</Text>
            </Pressable>
          ))}
        </View>
      </View>
      <TrackingBanner tracking={tracking} />
      {state.status === 'error' && (
        <View style={styles.banner}>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Thử lại" onPress={refresh} />
        </View>
      )}
      <FlatList
        data={showingFinished ? finished.items : state.items}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} />}
        ListFooterComponent={
          showingFinished && finished.error ? (
            <>
              <Notice tone="danger">{finished.error}</Notice>
              <Button
                title="Tải lại chuyến đã kết thúc"
                variant="secondary"
                onPress={() => loadFinished()}
              />
            </>
          ) : showingFinished && finished.more ? (
            <Button
              title="Tải thêm"
              variant="secondary"
              loading={finished.loading}
              onPress={() => loadFinished(finished.page + 1)}
            />
          ) : null
        }
        ListEmptyComponent={
          showingFinished && finished.loading ? (
            <Loading />
          ) : state.status === 'ready' && (!showingFinished || !finished.error) ? (
            <Text style={styles.empty}>
              {tab === 'active' ? 'Chưa có chuyến nào cần giao.' : 'Chưa có chuyến nào kết thúc.'}
            </Text>
          ) : null
        }
        renderItem={({ item }) => (
          <AssignmentCard assignment={item} onChanged={onChanged} onStale={refresh} />
        )}
      />
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  row: { flexDirection: 'row', gap: spacing(2) },
  header: { padding: spacing(4), paddingBottom: spacing(2), gap: spacing(3) },
  title: { fontSize: 22, fontWeight: '800', color: colors.text },
  tabs: {
    flexDirection: 'row',
    backgroundColor: colors.surfaceMuted,
    borderRadius: radius.full,
    padding: spacing(1),
  },
  tab: { flex: 1, paddingVertical: spacing(2), borderRadius: radius.full, alignItems: 'center' },
  tabActive: { backgroundColor: colors.surface },
  tabText: { color: colors.muted, fontWeight: '600' },
  tabTextActive: { color: colors.primary },
  banner: { paddingHorizontal: spacing(4), gap: spacing(2), marginBottom: spacing(2) },
  list: { padding: spacing(4), paddingTop: spacing(1), gap: spacing(3) },
  empty: { textAlign: 'center', color: colors.muted, marginTop: spacing(10) },
  cardHead: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing(2) },
  orderId: { fontSize: 16, fontWeight: '700', color: colors.text },
  muted: { color: colors.subtle, fontSize: 13 },
  info: { gap: spacing(0.5) },
  stops: { gap: spacing(2) },
  stop: {
    gap: spacing(2),
    paddingTop: spacing(2),
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  label: { color: colors.subtle, fontSize: 12, marginTop: spacing(1.5) },
  value: { color: colors.text, fontSize: 15 },
  cod: { color: colors.accent, fontWeight: '700' },
  link: { color: colors.primary, fontSize: 15, fontWeight: '600' },
})
