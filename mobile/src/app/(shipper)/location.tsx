import { router, useLocalSearchParams, type Href } from 'expo-router'
import { useState } from 'react'
import { View } from 'react-native'

import { AppText, Button, Screen, Skeleton, useToast } from '@/components/ui'
import { taskHref } from '@/features/shipper/actions'
import { LinesCard } from '@/features/shipper/components/cards'
import { useAssignments } from '@/features/shipper/hooks'
import { isTracking } from '@/features/shipper/lib/status'
import { tracker } from '@/features/shipper/location/tracker'
import { useAddress } from '@/features/shipper/location/useAddress'
import { useLocationPermission, useTrackerState } from '@/features/shipper/location/useLocation'
import { formatTime } from '@/lib/format'

const HOME = '/(shipper)' as Href

/**
 * Tab "Vị trí": GPS permission explainer (3.18), permission denied (3.21), GPS lost (3.22) and the
 * healthy / idle status. Opened with `?back=1` from the order flow so it returns there afterwards.
 */
export default function ShipperLocationScreen() {
  const { back } = useLocalSearchParams<{ back?: string }>()
  const perm = useLocationPermission()
  const track = useTrackerState()
  const toast = useToast()
  const q = useAssignments()
  const [busy, setBusy] = useState(false)

  const current = q.assignments.find(isTracking)
  const last =
    track.lastSent ??
    (current?.latitude != null && current.longitude != null && current.locationAt
      ? {
          latitude: current.latitude,
          longitude: current.longitude,
          at: new Date(current.locationAt).getTime(),
        }
      : null)
  const address = useAddress(last)

  const leave = () => {
    if (back && router.canGoBack()) router.back()
    else router.navigate(HOME)
  }
  const viewTask = () =>
    current ? router.navigate(taskHref(current.orderId)) : router.navigate(HOME)

  const allow = async () => {
    setBusy(true)
    try {
      if (await perm.request()) {
        if (back) leave()
      }
    } finally {
      setBusy(false)
    }
  }

  const recheck = async () => {
    setBusy(true)
    try {
      if (await perm.refresh()) {
        if (back) leave()
      } else if (perm.canAskAgain) {
        if (await perm.request()) {
          if (back) leave()
        }
      } else {
        await perm.openSettings()
      }
    } finally {
      setBusy(false)
    }
  }

  const reconnect = async () => {
    setBusy(true)
    try {
      const ok = await tracker.retryNow()
      toast.show(
        ok
          ? { type: 'success', message: 'Đã chia sẻ lại vị trí.' }
          : { type: 'error', message: 'Chưa kết nối lại được. Thử lại sau.' },
      )
      if (ok) viewTask()
    } finally {
      setBusy(false)
    }
  }

  let body
  if (perm.state === 'loading') {
    body = <Skeleton height={110} radius={10} />
  } else if (perm.state === 'undetermined') {
    body = (
      <>
        <AppText variant="h2">Quyền vị trí GPS</AppText>
        <LinesCard
          title="Chia sẻ vị trí khi giao đơn"
          lines={['Cho phép vị trí để người mua theo dõi đơn. Chia sẻ tự dừng khi đơn kết thúc.']}
        />
        <Button testID="shipper-gps-allow" title="Cho phép vị trí" loading={busy} onPress={allow} />
        <Button
          testID="shipper-gps-skip"
          kind="secondary"
          title="Chưa cho phép"
          disabled={busy}
          onPress={leave}
        />
      </>
    )
  } else if (perm.state === 'denied') {
    body = (
      <>
        <AppText variant="h2">Chưa có quyền vị trí</AppText>
        <LinesCard
          title="Bật vị trí để tiếp tục giao"
          lines={[
            'VMarket cần quyền vị trí trong quá trình giao. Mở cài đặt thiết bị, cho phép vị trí rồi quay lại kiểm tra.',
          ]}
        />
        <Button
          testID="shipper-gps-recheck"
          title="Kiểm tra lại quyền"
          loading={busy}
          onPress={recheck}
        />
        <Button
          testID="shipper-gps-back"
          kind="secondary"
          title="Quay lại đơn giao"
          disabled={busy}
          onPress={leave}
        />
      </>
    )
  } else if (track.status === 'lost') {
    body = (
      <>
        <AppText variant="h2">Mất kết nối vị trí</AppText>
        <LinesCard
          testID="shipper-gps-lost-card"
          title={last ? `Vị trí cuối · ${formatTime(last.at)}` : 'Chưa có vị trí'}
          lines={[
            last ? address : null,
            track.lostReason === 'services'
              ? 'GPS của thiết bị đang tắt. Bật dịch vụ vị trí rồi thử lại.'
              : 'Chưa nhận cập nhật mới. Kiểm tra GPS và kết nối mạng; vị trí cũ không phải vị trí trực tiếp.',
            track.queued ? 'Vị trí mới nhất sẽ được gửi ngay khi có kết nối.' : null,
          ]}
        />
        <Button
          testID="shipper-gps-reconnect"
          title="Thử kết nối lại"
          loading={busy}
          onPress={reconnect}
        />
        <Button
          testID="shipper-gps-view-task"
          kind="secondary"
          title="Xem đơn đang giao"
          disabled={busy}
          onPress={viewTask}
        />
      </>
    )
  } else if (track.status === 'sharing') {
    body = (
      <>
        <AppText variant="h2">Vị trí hiện tại</AppText>
        <LinesCard
          testID="shipper-gps-ok-card"
          title="Đang chia sẻ vị trí"
          lines={[
            last ? `${address} · cập nhật ${formatTime(last.at)}` : 'Đang lấy vị trí đầu tiên…',
            'Chia sẻ tự dừng khi đơn kết thúc.',
          ]}
        />
        <Button testID="shipper-gps-view-task" title="Xem đơn đang giao" onPress={viewTask} />
      </>
    )
  } else {
    body = (
      <>
        <AppText variant="h2">Vị trí</AppText>
        <LinesCard
          testID="shipper-gps-idle-card"
          title="Chưa chia sẻ vị trí"
          lines={[
            'Quyền vị trí đã sẵn sàng. VMarket chỉ chia sẻ vị trí khi bạn đang giao hàng (sau khi lấy hàng) và tự dừng khi đơn kết thúc.',
          ]}
        />
        <Button
          testID="shipper-gps-home"
          title="Về đơn giao"
          onPress={() => router.navigate(HOME)}
        />
      </>
    )
  }

  return (
    <Screen title="VMarket Shipper" role="shipper" testID="shipper-location">
      <View style={{ gap: 12 }}>{body}</View>
    </Screen>
  )
}
