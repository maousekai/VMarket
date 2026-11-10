import { router } from 'expo-router'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'
import { authHref } from '@/features/auth/nav'

/** Google sign-in has no backend endpoint yet, so this state is shown honestly instead of a fake login. */
export default function GoogleCancelledScreen() {
  return (
    <Screen title="Chưa đăng nhập Google" testID="google-cancelled-screen">
      <AppText>Đăng nhập bằng Google hiện chưa được hỗ trợ.</AppText>
      <InfoCard
        title="Thử lại"
        body="Vui lòng dùng email và mật khẩu để đăng nhập hoặc tạo tài khoản mới."
      />
      <Button
        testID="google-back"
        title="Về đăng nhập"
        onPress={() => router.replace(authHref('login'))}
      />
    </Screen>
  )
}
