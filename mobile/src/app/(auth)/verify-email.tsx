import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { getApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AppText, Button, Screen, TextField, useToast } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { isOtpInlineError } from '@/features/auth/errors'
import { authHref, returnToParam } from '@/features/auth/nav'
import { flushRegistrationDraft } from '@/features/auth/registrationDraft'
import { RESEND_COOLDOWN_SECONDS, useCountdown } from '@/features/auth/useCountdown'
import { validateOtp } from '@/features/auth/validation'

const OTP_SEND_FAILED = 'Chưa gửi được mã xác thực. Vui lòng nhấn "Gửi lại mã".'

export default function VerifyEmailScreen() {
  const { user, isPending, verifyOtp, requestOtp, logout } = useAuth()
  const toast = useToast()
  const params = useLocalSearchParams<{ email?: string; sent?: string; returnTo?: string }>()
  const returnTo = returnToParam(params.returnTo)
  const email = params.email || user?.email || ''

  const [phase, setPhase] = useState<'verify' | 'expired'>('verify')
  const [resent, setResent] = useState(false)
  const [otp, setOtp] = useState('')
  const [otpError, setOtpError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(params.sent === '0' ? OTP_SEND_FAILED : null)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const countdown = useCountdown(params.sent === '1' ? RESEND_COOLDOWN_SECONDS : 0)

  async function guarded(task: () => Promise<void>) {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    try {
      await task()
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  async function verifyOnce() {
    try {
      return await verifyOtp(email, otp)
    } catch (failure) {
      // A parallel account creation lost the race: the same code succeeds when sent again.
      if (getApiError(failure).code !== 'REGISTRATION_CONFLICT') throw failure
      return verifyOtp(email, otp)
    }
  }

  const submit = () =>
    guarded(async () => {
      const invalid = validateOtp(otp)
      setOtpError(invalid)
      setError(null)
      if (invalid) return
      try {
        const verified = await verifyOnce()
        // The (auth) layout now redirects to returnTo / home; finish the profile in the background.
        toast.show({ type: 'success', message: 'Xác thực email thành công' })
        const saved = await flushRegistrationDraft(verified.email)
        if (!saved) {
          toast.show({
            type: 'info',
            message: 'Chưa lưu được họ tên và số điện thoại. Bạn có thể bổ sung trong Tài khoản.',
          })
        }
      } catch (failure) {
        const apiError = getApiError(failure)
        if (apiError.code === 'OTP_EXPIRED') {
          setPhase('expired')
          setOtp('')
        } else if (isOtpInlineError(apiError)) {
          setOtpError(apiError.message)
          setOtp('')
        } else {
          setError(apiError.message)
        }
      }
    })

  const sendCode = () =>
    guarded(async () => {
      setError(null)
      setOtpError(undefined)
      try {
        await requestOtp(email)
        setOtp('')
        setPhase('verify')
        setResent(true)
        countdown.start()
      } catch (failure) {
        const apiError = getApiError(failure)
        setError(apiError.message)
        if (['OTP_RESEND_TOO_SOON', 'EMAIL_SEND_FAILED'].includes(apiError.code)) countdown.start()
      }
    })

  async function backToLogin() {
    if (isPending) await logout()
    router.replace(authHref('login', { returnTo }))
  }

  if (!email) {
    return (
      <Screen title="Xác thực email" testID="verify-email-screen">
        <AppText>Chưa có email cần xác thực. Hãy đăng nhập hoặc đăng ký để nhận mã.</AppText>
        <Button title="Về đăng nhập" onPress={backToLogin} />
      </Screen>
    )
  }

  if (phase === 'expired') {
    return (
      <Screen title="Mã xác thực hết hạn" testID="verify-email-screen">
        <AppText>Yêu cầu mã mới để tiếp tục</AppText>
        <FormError testID="verify-error" message={error} />
        <Button
          testID="verify-send-new"
          title={countdown.remaining ? `Gửi mã mới (${countdown.remaining}s)` : 'Gửi mã mới'}
          onPress={sendCode}
          loading={busy}
          disabled={countdown.remaining > 0}
        />
        <Button
          testID="verify-back-login"
          title="Về đăng nhập"
          kind="secondary"
          disabled={busy}
          onPress={backToLogin}
        />
      </Screen>
    )
  }

  const cooling = countdown.remaining > 0
  const justResent = resent && cooling
  let resendTitle = 'Gửi lại mã'
  if (cooling) {
    resendTitle = `${justResent ? 'Đã gửi lại mã' : 'Gửi lại mã'} (${countdown.remaining}s)`
  }

  return (
    <Screen title="Xác thực email" testID="verify-email-screen">
      <AppText>
        {justResent ? 'Mã xác thực mới đã gửi tới' : 'Nhập mã được gửi tới'} {email}
      </AppText>
      <TextField
        testID="verify-otp"
        label="Mã xác thực"
        required
        value={otp}
        onChangeText={(text) => setOtp(text.replace(/\D/g, ''))}
        placeholder="123456"
        keyboardType="number-pad"
        maxLength={6}
        autoComplete="one-time-code"
        textContentType="oneTimeCode"
        returnKeyType="go"
        onSubmitEditing={submit}
        error={otpError}
        disabled={busy}
      />
      <FormError testID="verify-error" message={error} />
      <Button testID="verify-submit" title="Xác thực" onPress={submit} loading={busy} />
      <Button
        testID="verify-resend"
        title={resendTitle}
        kind="secondary"
        onPress={sendCode}
        disabled={busy || cooling}
      />
      <AppText variant="caption">Có thể gửi lại mã nếu bạn chưa nhận được email.</AppText>
      {isPending ? (
        <Button
          testID="verify-logout"
          title="Đăng xuất"
          kind="neutral"
          disabled={busy}
          onPress={backToLogin}
        />
      ) : null}
    </Screen>
  )
}
