import { router, useLocalSearchParams } from 'expo-router'

import { AppText, Button, Screen } from '@/components/ui'
import { authHref, returnToParam } from '@/features/auth/nav'

/** Shown by AuthProvider when a signed-in session is invalidated (refresh token rejected). */
export default function SessionExpiredScreen() {
  const returnTo = returnToParam(useLocalSearchParams<{ returnTo?: string }>().returnTo)
  return (
    <Screen title="Phiên đã hết hạn" testID="session-expired-screen">
      <AppText>Đăng nhập lại để tiếp tục truy cập tài khoản.</AppText>
      <Button
        testID="session-login"
        title="Đăng nhập lại"
        onPress={() => router.replace(authHref('login', { returnTo }))}
      />
      <Button
        testID="session-home"
        title="Về trang chủ"
        kind="secondary"
        onPress={() => router.replace('/(buyer)')}
      />
    </Screen>
  )
}
