import { useCallback, useEffect, useState } from 'react'
import { FlatList, Linking, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { getApiError } from '../api/client'
import {
  DELIVERY_STATUS_LABELS,
  formatMoney,
  isTerminalStatus,
  listMyAssignments,
  orderAddress,
  parseOrderSnapshot,
  retryAssignment,
  shipperActions,
  shortOrderId,
  updateAssignmentStatus,
} from '../api/deliveryApi'
import { Badge, Button, Card, Loading, Notice } from '../components/ui'
import { colors, radius, spacing } from '../theme'
import { DeliverSheet, FailSheet } from './ActionSheet'
import useShipperTracking from './useShipperTracking'

const TABS = [
  ['active', 'Đang xử lý'],
  ['done', 'Đã kết thúc'],
]

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

function TrackingBanner({ tracking }) {
  if (!tracking.trackingCount) return null
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
  return (
    <View style={styles.banner}>
      <Notice tone={tracking.error ? 'warning' : 'success'}>
        {tracking.mode === 'background'
          ? `Đang chia sẻ vị trí cho ${tracking.trackingCount} chuyến, kể cả khi app chạy nền.`
          : `Đang chia sẻ vị trí cho ${tracking.trackingCount} chuyến khi app đang mở${sent}.`}
        {tracking.error ? `\n${tracking.error}` : ''}
      </Notice>
    </View>
  )
}

function AssignmentCard({ assignment, onChanged, onStale }) {
  const order = parseOrderSnapshot(assignment.orderSnapshot)
  const [sheet, setSheet] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const cod = assignment.paymentMethod === 'COD' && Number(assignment.codAmount) > 0

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      onChanged(await action())
      setSheet('')
    } catch (failure) {
      const apiError = getApiError(failure)
      setError(apiError.message)
      if (apiError.code === 'STALE_DELIVERY') onStale()
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
          <Text style={styles.muted}>Lần giao {assignment.attempt || 1}/3</Text>
        </View>
        <Badge
          label={DELIVERY_STATUS_LABELS[assignment.status] || assignment.status}
          tone={badgeTone(assignment.status)}
        />
      </View>
      <View style={styles.info}>
        <Text style={styles.label}>Người nhận</Text>
        <Text style={styles.value}>{order.recipientName || '—'}</Text>
        {order.phone ? (
          <Pressable accessibilityRole="link" onPress={() => Linking.openURL(`tel:${order.phone}`)}>
            <Text style={styles.link}>{order.phone}</Text>
          </Pressable>
        ) : null}
        <Text style={styles.label}>Địa chỉ</Text>
        <Text style={styles.value}>
          {orderAddress(order) ||
            [assignment.district, assignment.province].filter(Boolean).join(', ')}
        </Text>
        {order.note ? (
          <>
            <Text style={styles.label}>Ghi chú</Text>
            <Text style={styles.value}>{order.note}</Text>
          </>
        ) : null}
        <Text style={styles.label}>Thu hộ</Text>
        <Text style={[styles.value, cod && styles.cod]}>
          {cod ? `COD ${formatMoney(assignment.codAmount)}` : 'Đã thanh toán trước'}
        </Text>
      </View>
      {error && !sheet ? <Notice tone="danger">{error}</Notice> : null}
      {shipperActions(assignment).map((action) => (
        <Button
          key={action.label}
          title={action.label}
          variant={action.danger ? 'danger' : 'primary'}
          loading={busy && !sheet}
          disabled={busy}
          onPress={() => onAction(action)}
        />
      ))}
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
  const [state, setState] = useState({ status: 'loading', items: [], error: '' })
  const [refreshing, setRefreshing] = useState(false)

  const load = useCallback(async () => {
    try {
      const items = await listMyAssignments()
      setState({ status: 'ready', items: items || [], error: '' })
    } catch (failure) {
      setState((previous) => ({
        ...previous,
        status: 'error',
        error: getApiError(failure).message,
      }))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const tracking = useShipperTracking(state.items)

  const onChanged = (updated) =>
    setState((previous) => ({
      ...previous,
      items: previous.items.map((item) => (item.id === updated.id ? updated : item)),
    }))

  async function refresh() {
    setRefreshing(true)
    await load()
    setRefreshing(false)
  }

  const visible = state.items.filter((item) =>
    tab === 'active' ? !isTerminalStatus(item.status) : isTerminalStatus(item.status),
  )

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
              onPress={() => setTab(value)}
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
        data={visible}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.list}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} />}
        ListEmptyComponent={
          state.status === 'ready' ? (
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
  label: { color: colors.subtle, fontSize: 12, marginTop: spacing(1.5) },
  value: { color: colors.text, fontSize: 15 },
  cod: { color: colors.accent, fontWeight: '700' },
  link: { color: colors.primary, fontSize: 15, fontWeight: '600' },
})
