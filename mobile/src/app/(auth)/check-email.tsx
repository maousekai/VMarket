import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { forgotPassword } from '@/api/auth'
import { getApiError } from '@/api/client'
import { AppText, Button, InfoCard, Screen, useToast } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { authHref } from '@/features/auth/nav'
import { RESEND_COOLDOWN_SECONDS, useCountdown } from '@/features/auth/useCountdown'

export default function CheckEmailScreen() {
  const email = useLocalSearchParams<{ email?: string }>().email ?? ''
  const toast = useToast()
  const countdown = useCountdown(RESEND_COOLDOWN_SECONDS)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)

  async function resend() {
    if (submitting.current || !email) return
    submitting.current = true
    setBusy(true)
    setError(null)
    try {
      await forgotPassword(email)
      toast.show({ type: 'success', message: 'Đã gửi lại email' })
      countdown.start()
    } catch (failure) {
      setError(getApiError(failure).message)
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <Screen title="Kiểm tra email" testID="check-email-screen">
      <AppText>Nếu email có tài khoản, mã đặt lại đã được gửi.</AppText>
      <InfoCard
        title="Mã có thời hạn"
        body="Kiểm tra hộp thư và thư rác. Nhập mã trong email để đặt lại mật khẩu."
      />
      <FormError testID="check-email-error" message={error} />
      <Button
        testID="check-email-enter-code"
        title="Nhập mã đặt lại"
        onPress={() => router.push(authHref('reset-password', { email }))}
      />
      <Button
        testID="check-email-resend"
        title={countdown.remaining ? `Gửi lại email (${countdown.remaining}s)` : 'Gửi lại email'}
        kind="secondary"
        onPress={resend}
        loading={busy}
        disabled={countdown.remaining > 0 || !email}
      />
      <Button
        testID="check-email-back"
        title="Về đăng nhập"
        kind="secondary"
        disabled={busy}
        onPress={() => router.replace(authHref('login'))}
      />
    </Screen>
  )
}
