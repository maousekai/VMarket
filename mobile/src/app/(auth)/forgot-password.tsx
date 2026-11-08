import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { forgotPassword } from '@/api/auth'
import { getApiError } from '@/api/client'
import { AppText, Button, Screen, TextField } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { authHref } from '@/features/auth/nav'
import { emailError } from '@/features/auth/validation'

export default function ForgotPasswordScreen() {
  const params = useLocalSearchParams<{ email?: string }>()
  const [email, setEmail] = useState(params.email ?? '')
  const [fieldError, setFieldError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)

  async function submit() {
    if (submitting.current) return
    const invalid = emailError(email)
    setFieldError(invalid)
    setError(null)
    if (invalid) return
    submitting.current = true
    setBusy(true)
    try {
      const address = email.trim().toLowerCase()
      await forgotPassword(address)
      // The response is always generic, so a 200 always continues.
      router.push(authHref('check-email', { email: address }))
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldError(apiError.fieldErrors.email)
      setError(apiError.fieldErrors.email ? null : apiError.message)
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <Screen title="Quên mật khẩu" testID="forgot-password-screen">
      <AppText>Nhận mã đặt lại mật khẩu qua email</AppText>
      <TextField
        testID="forgot-email"
        label="Email"
        required
        value={email}
        onChangeText={setEmail}
        placeholder="minhtuan@example.com"
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        returnKeyType="go"
        onSubmitEditing={submit}
        error={fieldError}
        disabled={busy}
      />
      <FormError testID="forgot-error" message={error} />
      <Button testID="forgot-submit" title="Gửi mã" onPress={submit} loading={busy} />
      <Button
        testID="forgot-back"
        title="Về đăng nhập"
        kind="secondary"
        disabled={busy}
        onPress={() => router.replace(authHref('login'))}
      />
    </Screen>
  )
}
