import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { resetPassword } from '@/api/auth'
import { getApiError } from '@/api/client'
import { AppText, Button, Screen, TextField, useToast } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { RESET_NEEDS_NEW_CODE } from '@/features/auth/errors'
import { authHref } from '@/features/auth/nav'
import { validateReset, type FieldErrors } from '@/features/auth/validation'

type Field = 'email' | 'code' | 'newPassword' | 'confirmPassword'

export default function ResetPasswordScreen() {
  const toast = useToast()
  const params = useLocalSearchParams<{ email?: string; code?: string }>()
  const [email, setEmail] = useState(params.email ?? '')
  const [code, setCode] = useState(params.code ?? '')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [fields, setFields] = useState<FieldErrors<Field>>({})
  const [error, setError] = useState<string | null>(null)
  const [needsNewCode, setNeedsNewCode] = useState(false)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)

  async function submit() {
    if (submitting.current) return
    const invalid = validateReset({ email, code, newPassword, confirmPassword })
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    submitting.current = true
    setBusy(true)
    try {
      await resetPassword(email.trim().toLowerCase(), code, newPassword)
      toast.show({ type: 'success', message: 'Đặt lại mật khẩu thành công. Vui lòng đăng nhập.' })
      router.replace(authHref('login', { email: email.trim() }))
    } catch (failure) {
      const apiError = getApiError(failure)
      if (apiError.code === 'PASSWORD_RESET_INVALID') {
        setFields({ code: apiError.message })
      } else if (apiError.code === 'PASSWORD_UNCHANGED') {
        setFields({ newPassword: apiError.message })
      } else {
        setFields(apiError.fieldErrors)
        setError(Object.keys(apiError.fieldErrors).length ? null : apiError.message)
        setNeedsNewCode(RESET_NEEDS_NEW_CODE.has(apiError.code))
      }
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <Screen title="Đặt lại mật khẩu" testID="reset-password-screen">
      <AppText>Nhập mã trong email và mật khẩu mới</AppText>
      {params.email ? null : (
        <TextField
          testID="reset-email"
          label="Email"
          required
          value={email}
          onChangeText={setEmail}
          placeholder="minhtuan@example.com"
          keyboardType="email-address"
          autoCapitalize="none"
          autoComplete="email"
          error={fields.email}
          disabled={busy}
        />
      )}
      <TextField
        testID="reset-code"
        label="Mã đặt lại mật khẩu"
        required
        value={code}
        onChangeText={(text) => setCode(text.replace(/\D/g, ''))}
        placeholder="123456"
        keyboardType="number-pad"
        maxLength={6}
        autoComplete="one-time-code"
        textContentType="oneTimeCode"
        error={fields.code}
        disabled={busy}
      />
      <TextField
        testID="reset-password"
        label="Mật khẩu mới"
        required
        value={newPassword}
        onChangeText={setNewPassword}
        placeholder="••••••••••••"
        secure
        autoComplete="password-new"
        textContentType="newPassword"
        helper="8–32 ký tự, có ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt"
        error={fields.newPassword}
        disabled={busy}
      />
      <TextField
        testID="reset-confirm"
        label="Xác nhận mật khẩu"
        required
        value={confirmPassword}
        onChangeText={setConfirmPassword}
        placeholder="••••••••••••"
        secure
        autoComplete="password-new"
        textContentType="newPassword"
        returnKeyType="go"
        onSubmitEditing={submit}
        error={fields.confirmPassword}
        disabled={busy}
      />
      <FormError testID="reset-error" message={error} />
      <Button testID="reset-submit" title="Lưu mật khẩu mới" onPress={submit} loading={busy} />
      {needsNewCode ? (
        <Button
          testID="reset-new-code"
          title="Yêu cầu mã mới"
          kind="secondary"
          disabled={busy}
          onPress={() => router.replace(authHref('forgot-password', { email: email.trim() }))}
        />
      ) : null}
      <Button
        testID="reset-back"
        title="Về đăng nhập"
        kind="secondary"
        disabled={busy}
        onPress={() => router.replace(authHref('login'))}
      />
    </Screen>
  )
}
