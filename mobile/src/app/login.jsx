import { useState } from 'react'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { Body, Button, Field, Heading, Notice, Screen } from '../components/ui'

/** Dang nhap (frame 3.1). Dang nhap Google duoc luoc bo (review PR 68 lan 4) nen khong hien nut. */
export default function LoginScreen() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      await login({ email, password })
      // AuthProvider nhan token moi -> RootNavigator tu chuyen sang giao dien theo vai tro.
    } catch (failure) {
      setError(getApiError(failure))
      setBusy(false)
    }
  }

  const ready = email.trim().length > 3 && password.length > 0
  return (
    <Screen title="VMarket">
      <Heading>Mua gần, chọn đúng.</Heading>
      <Body muted>Khám phá hàng hóa từ các gian hàng tại Đà Nẵng.</Body>
      <Field
        label="Email"
        placeholder="ban@example.com"
        value={email}
        onChangeText={setEmail}
        autoCapitalize="none"
        autoComplete="email"
        keyboardType="email-address"
        textContentType="emailAddress"
        error={error?.fieldErrors?.email}
      />
      <Field
        label="Mật khẩu"
        value={password}
        onChangeText={setPassword}
        secure
        autoComplete="password"
        textContentType="password"
        error={error?.fieldErrors?.password}
        onSubmitEditing={ready ? submit : undefined}
      />
      {error && !Object.keys(error.fieldErrors || {}).length && (
        <Notice tone="danger">{error.message}</Notice>
      )}
      <Button title="Đăng nhập" onPress={submit} loading={busy} disabled={!ready} />
      <Button
        title="Quên mật khẩu?"
        variant="secondary"
        onPress={() => router.push('/forgot-password')}
      />
      <Button title="Tạo tài khoản" variant="secondary" onPress={() => router.push('/register')} />
    </Screen>
  )
}
