import { Linking, Platform, View } from 'react-native'
import { Button, Notice } from '../components/ui'
import { makeStyles, spacing } from '../theme'

const timeFormat = new Intl.DateTimeFormat('vi-VN', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

export const openLocationSettings = () =>
  Platform.OS === 'android'
    ? Linking.sendIntent('android.settings.LOCATION_SOURCE_SETTINGS').catch(() =>
        Linking.openSettings(),
      )
    : Linking.openSettings()

/**
 * Huong dan cho ROM chan chay nen (Xiaomi, Oppo, Vivo, Meizu...): cac may nay chan JobService cua
 * expo-task-manager khi tat man hinh, nen GPS nen dung han (review PR 68 lan 4, muc 1.2).
 */
export const BACKGROUND_HELP =
  'Một số máy (Xiaomi, Oppo, Vivo, Meizu…) chặn ứng dụng chạy nền khi tắt màn hình. Trong cài đặt của VMarket, bật "Cho phép chạy nền / Tự khởi động" và tắt tối ưu pin. Khi đang giao, ứng dụng giữ màn hình sáng; hãy để ứng dụng mở.'

/** Trang thai chia se vi tri: quyen, dinh vi tat, bi thu hoi, dang gui, mat tin hieu. */
export default function TrackingBanner({ tracking }) {
  const styles = useStyles()
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
      <Notice tone="danger">
        {`Đã dừng chia sẻ vị trí: ${tracking.error || 'bạn không còn quyền với chuyến này.'}`}
      </Notice>
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
      {tracking.stalled ? (
        <>
          <Notice tone="danger">
            {`Mất kết nối GPS: chưa gửi được vị trí hơn 1 phút, người mua đang thấy vị trí cũ. ${BACKGROUND_HELP}`}
          </Notice>
          <View style={styles.row}>
            <Button
              title="Mở cài đặt ứng dụng"
              onPress={() => Linking.openSettings()}
              style={styles.flex}
            />
            <Button
              title="Thử lại"
              variant="secondary"
              onPress={tracking.retry}
              style={styles.flex}
            />
          </View>
        </>
      ) : (
        <Notice tone={tracking.error || !tracking.sharing ? 'warning' : 'success'}>
          {message}
          {tracking.error ? `\n${tracking.error}` : ''}
        </Notice>
      )}
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

const useStyles = makeStyles(() => ({
  banner: { gap: spacing(2) },
  row: { flexDirection: 'row', gap: spacing(2) },
  flex: { flex: 1 },
}))
