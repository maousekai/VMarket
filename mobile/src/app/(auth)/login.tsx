import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { getApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AppText, Button, Screen, TextField } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { loginErrorMessage, parseLockMinutes } from '@/features/auth/errors'
import { authHref, returnToParam } from '@/features/auth/nav'
import { flushRegistrationDraft } from '@/features/auth/registrationDraft'
import { validateLogin, type FieldErrors } from '@/features/auth/validation'

export default function LoginScreen() {
  const { login, requestOtp } = useAuth()
  const params = useLocalSearchParams<{ returnTo?: string; email?: string }>()
  const returnTo = returnToParam(params.returnTo)
  const [email, setEmail] = useState(params.email ?? '')
  const [password, setPassword] = useState('')
  const [fields, setFields] = useState<FieldErrors<'email' | 'password'>>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)

  async function submit() {
    if (submitting.current) return
    const invalid = validateLogin({ email, password })
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    submitting.current = true
    setBusy(true)
    try {
      const user = await login(email, password)
      if (user.status === 'PENDING') {
        // Unverified account: send a fresh code and continue on the verify screen.
        const sent = await requestOtp(user.email).then(
          () => '1',
          () => '0',
        )
        router.replace(authHref('verify-email', { email: user.email, sent, returnTo }))
      } else {
        // Redirect to returnTo / home is done by the (auth) layout.
        void flushRegistrationDraft(user.email)
      }
    } catch (failure) {
      const apiError = getApiError(failure)
      if (apiError.code === 'ACCOUNT_LOCKED') {
        const minutes = parseLockMinutes(apiError.message)
        router.push(
          authHref('locked', {
            minutes: minutes ? String(minutes) : undefined,
            email: email.trim(),
          }),
        )
      } else {
        setFields(apiError.fieldErrors)
        setError(loginErrorMessage(apiError))
        if (apiError.code === 'INVALID_CREDENTIALS') setPassword('')
      }
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <Screen title="VMarket" testID="login-screen">
      <AppText variant="h2">Mua gần, chọn đúng.</AppText>
      <AppText color="textSecondary">Khám phá hàng hóa từ các gian hàng tại Đà Nẵng.</AppText>
      <TextField
        testID="login-email"
        label="Email"
        value={email}
        onChangeText={setEmail}
        placeholder="ban@example.com"
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        textContentType="username"
        error={fields.email}
        disabled={busy}
      />
      <TextField
        testID="login-password"
        label="Mật khẩu"
        value={password}
        onChangeText={setPassword}
        placeholder="••••••••"
        secure
        autoComplete="current-password"
        textContentType="password"
        returnKeyType="go"
        onSubmitEditing={submit}
        error={fields.password}
        disabled={busy}
      />
      <FormError testID="login-error" message={error} />
      <Button testID="login-submit" title="Đăng nhập" onPress={submit} loading={busy} />
      <Button
        testID="login-google"
        title="Tiếp tục với Google"
        kind="secondary"
        disabled={busy}
        onPress={() => router.push(authHref('google-cancelled'))}
      />
      <Button
        testID="login-forgot"
        title="Quên mật khẩu?"
        kind="secondary"
        disabled={busy}
        onPress={() => router.push(authHref('forgot-password', { email: email.trim() }))}
      />
      <Button
        testID="login-register"
        title="Tạo tài khoản"
        kind="secondary"
        disabled={busy}
        onPress={() => router.push(authHref('register', { returnTo }))}
      />
    </Screen>
  )
}
