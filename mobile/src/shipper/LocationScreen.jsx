import { useCallback, useEffect, useState } from 'react'
import { AppState, Linking } from 'react-native'
import * as Location from 'expo-location'
import { useAuth } from '../auth/AuthProvider'
import { Body, Button, Card, Heading, Lines, Notice, Screen } from '../components/ui'
import { stopTracking } from './locationTracking'
import { useShipper } from './ShipperProvider'
import TrackingBanner, { BACKGROUND_HELP, openLocationSettings } from './TrackingBanner'

/**
 * Tab "Vi tri" (frame 3.18 quyen GPS, 3.21 bi tu choi, 3.22 mat ket noi): trang thai chia se vi tri,
 * huong dan chay nen tren cac ROM chan tac vu nen, va tai khoan / dang xuat cua shipper.
 */
export default function LocationScreen() {
  const { session, logout } = useAuth()
  const { tracking, load } = useShipper()
  const [permission, setPermission] = useState(null)
  const [servicesOn, setServicesOn] = useState(true)
  const [signingOut, setSigningOut] = useState(false)

  const check = useCallback(async () => {
    const result = await Location.getForegroundPermissionsAsync().catch(() => null)
    setPermission(result?.status || 'undetermined')
    setServicesOn(await Location.hasServicesEnabledAsync().catch(() => true))
  }, [])

  useEffect(() => {
    check()
    const subscription = AppState.addEventListener('change', (next) => {
      if (next === 'active') check()
    })
    return () => subscription.remove()
  }, [check])

  async function allow() {
    const result = await Location.requestForegroundPermissionsAsync().catch(() => null)
    setPermission(result?.status || 'denied')
    if (result?.status === 'granted') load()
  }

  async function signOut() {
    setSigningOut(true)
    // Khong de GPS tiep tuc chay nen sau khi shipper dang xuat.
    await stopTracking().catch(() => {})
    await logout()
  }

  return (
    <Screen title="VMarket Shipper" role="shipper">
      <Heading>Quyền vị trí GPS</Heading>
      {permission === 'granted' ? (
        <Card title="Đã cho phép vị trí">
          <Body>
            Vị trí chỉ được chia sẻ khi bạn đã lấy hàng và tự dừng khi đơn kết thúc. Vào ca không
            bật GPS.
          </Body>
        </Card>
      ) : permission === 'denied' ? (
        <>
          <Notice tone="danger">
            Bạn đã từ chối quyền vị trí. Người mua sẽ không thấy hành trình và bạn chưa xác nhận lấy
            hàng được.
          </Notice>
          <Button title="Mở cài đặt" onPress={() => Linking.openSettings()} />
        </>
      ) : (
        <>
          <Card title="Chia sẻ vị trí khi giao đơn">
            <Body>
              Cho phép vị trí để người mua theo dõi đơn. Chia sẻ sẽ tự dừng khi đơn kết thúc.
            </Body>
          </Card>
          <Button title="Cho phép vị trí" onPress={allow} />
        </>
      )}
      {!servicesOn && (
        <>
          <Notice tone="danger">Định vị (GPS) của máy đang tắt.</Notice>
          <Button title="Bật định vị" variant="secondary" onPress={openLocationSettings} />
        </>
      )}
      <TrackingBanner tracking={tracking} />
      {!tracking.trackingCount && (
        <Body muted>Chưa có chuyến nào cần gửi vị trí (chỉ gửi sau khi đã lấy hàng).</Body>
      )}
      <Card title="Khi tắt màn hình">
        <Body>{BACKGROUND_HELP}</Body>
      </Card>
      <Button
        title="Mở cài đặt ứng dụng"
        variant="secondary"
        onPress={() => Linking.openSettings()}
      />
      <Card title="Tài khoản">
        <Lines lines={[session?.username || null, session?.email || null, 'Người giao hàng']} />
      </Card>
      <Button title="Đăng xuất" variant="secondary" loading={signingOut} onPress={signOut} />
    </Screen>
  )
}
