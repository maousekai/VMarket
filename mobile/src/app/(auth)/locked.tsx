import { router, useLocalSearchParams } from 'expo-router'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'
import { authHref } from '@/features/auth/nav'

export default function LockedScreen() {
  const { minutes, email } = useLocalSearchParams<{ minutes?: string; email?: string }>()
  return (
    <Screen title="Tạm khóa đăng nhập" testID="locked-screen">
      <AppText>
        Bạn đã nhập sai 5 lần liên tiếp.{minutes ? ` Thử lại sau khoảng ${minutes} phút.` : ''}
      </AppText>
      <InfoCard
        title="Bảo vệ tài khoản"
        body="Thử lại sau thời gian khóa tạm thời hoặc dùng chức năng quên mật khẩu."
      />
      <Button
        testID="locked-forgot"
        title="Quên mật khẩu"
        onPress={() => router.replace(authHref('forgot-password', { email }))}
      />
      <Button
        testID="locked-back"
        title="Về đăng nhập"
        kind="secondary"
        onPress={() => router.replace(authHref('login', { email }))}
      />
    </Screen>
  )
}
