import { router, useLocalSearchParams } from 'expo-router'
import { useRef, useState } from 'react'

import { getApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AppText, Button, Screen, TextField } from '@/components/ui'
import { FormError } from '@/features/auth/components/FormError'
import { registerFieldErrors } from '@/features/auth/errors'
import { authHref, returnToParam } from '@/features/auth/nav'
import { clearDraft, saveDraft } from '@/features/auth/registrationDraft'
import { validateRegister, type FieldErrors, type RegisterValues } from '@/features/auth/validation'
import { normalizePhone } from '@/lib/format'

const EMPTY: RegisterValues = {
  email: '',
  username: '',
  fullName: '',
  phone: '',
  password: '',
  confirmPassword: '',
}

export default function RegisterScreen() {
  const { registerAccount, requestOtp } = useAuth()
  const returnTo = returnToParam(useLocalSearchParams<{ returnTo?: string }>().returnTo)
  const [values, setValues] = useState(EMPTY)
  const [fields, setFields] = useState<FieldErrors>({})
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)

  const set = (key: keyof RegisterValues) => (text: string) =>
    setValues((prev) => ({ ...prev, [key]: text }))

  async function submit() {
    if (submitting.current) return
    const normalized = { ...values, phone: normalizePhone(values.phone) }
    const invalid = validateRegister(normalized)
    setFields(invalid)
    setError(null)
    if (Object.keys(invalid).length) return
    submitting.current = true
    setBusy(true)
    try {
      const email = normalized.email.trim().toLowerCase()
      await clearDraft()
      await registerAccount({
        email,
        username: normalized.username.trim(),
        password: normalized.password,
      })
      await saveDraft({ email, fullName: normalized.fullName.trim(), phone: normalized.phone })
      const sent = await requestOtp(email).then(
        () => '1',
        () => '0',
      )
      router.replace(authHref('verify-email', { email, sent, returnTo }))
    } catch (failure) {
      const apiError = getApiError(failure)
      const byField = registerFieldErrors(apiError)
      setFields(byField)
      setError(Object.keys(byField).length ? null : apiError.message)
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return (
    <Screen title="Đăng ký VMarket" testID="register-screen">
      <AppText>Tạo tài khoản bằng email</AppText>
      <TextField
        testID="register-email"
        label="Email"
        required
        value={values.email}
        onChangeText={set('email')}
        placeholder="minhtuan@example.com"
        keyboardType="email-address"
        autoCapitalize="none"
        autoComplete="email"
        textContentType="emailAddress"
        maxLength={320}
        error={fields.email}
        disabled={busy}
      />
      <TextField
        testID="register-username"
        label="Tên tài khoản"
        required
        value={values.username}
        onChangeText={set('username')}
        placeholder="minhtuan"
        autoCapitalize="none"
        autoComplete="username-new"
        textContentType="username"
        maxLength={50}
        helper="3–50 ký tự: chữ, số và . _ -"
        error={fields.username}
        disabled={busy}
      />
      <TextField
        testID="register-fullname"
        label="Họ và tên"
        value={values.fullName}
        onChangeText={set('fullName')}
        placeholder="Nguyễn Minh Tuấn"
        autoComplete="name"
        textContentType="name"
        maxLength={100}
        error={fields.fullName}
        disabled={busy}
      />
      <TextField
        testID="register-phone"
        label="Số điện thoại"
        value={values.phone}
        onChangeText={set('phone')}
        placeholder="0912345678"
        keyboardType="phone-pad"
        autoComplete="tel"
        textContentType="telephoneNumber"
        maxLength={15}
        error={fields.phone}
        disabled={busy}
      />
      <TextField
        testID="register-password"
        label="Mật khẩu"
        required
        value={values.password}
        onChangeText={set('password')}
        placeholder="••••••••••••"
        secure
        autoComplete="password-new"
        textContentType="newPassword"
        helper="8–32 ký tự, có ít nhất 1 chữ hoa, 1 số và 1 ký tự đặc biệt"
        error={fields.password}
        disabled={busy}
      />
      <TextField
        testID="register-confirm"
        label="Xác nhận mật khẩu"
        required
        value={values.confirmPassword}
        onChangeText={set('confirmPassword')}
        placeholder="••••••••••••"
        secure
        autoComplete="password-new"
        textContentType="newPassword"
        returnKeyType="go"
        onSubmitEditing={submit}
        error={fields.confirmPassword}
        disabled={busy}
      />
      <FormError testID="register-error" message={error} />
      <Button testID="register-submit" title="Đăng ký" onPress={submit} loading={busy} />
      <Button
        testID="register-login"
        title="Đã có tài khoản"
        kind="secondary"
        disabled={busy}
        onPress={() => router.replace(authHref('login', { returnTo }))}
      />
    </Screen>
  )
}
