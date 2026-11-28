import { useState } from 'react'
import { Link } from 'expo-router'
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import {
  registerBuyer,
  requestPasswordReset,
  requestRegistrationOtp,
  resetPassword,
  verifyRegistrationOtp,
} from '../api/authApi'
import { getApiError } from '../api/client'
import { Button, Field, Notice } from '../components/ui'
import { colors, spacing } from '../theme'

export default function AccountAccessScreen({ register = false }) {
  const [step, setStep] = useState('email')
  const [email, setEmail] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const address = email.trim()
  const verifying = step === 'code'
  const title = register ? 'Đăng ký người mua' : 'Quên mật khẩu'
  const requestCode = register ? requestRegistrationOtp : requestPasswordReset

  async function run(action) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(getApiError(failure))
    } finally {
      setBusy(false)
    }
  }

  const start = () =>
    run(async () => {
      if (register) {
        await registerBuyer({ email: address, username: username.trim(), password })
        setPassword('')
        setStep('code')
        await requestCode(address)
      } else {
        await requestCode(address)
        setStep('code')
      }
    })

  const confirm = () =>
    run(async () => {
      if (register) await verifyRegistrationOtp({ email: address, otp: code })
      else {
        await resetPassword({ email: address, code, newPassword: password })
        setPassword('')
        setStep('done')
      }
    })

  return (
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <Text accessibilityRole="header" style={styles.title}>
            {title}
          </Text>
          {step === 'done' ? (
            <Notice tone="success">Đã đổi mật khẩu. Hãy đăng nhập bằng mật khẩu mới.</Notice>
          ) : (
            <>
              {verifying ? (
                <Text style={styles.description}>Nhập mã xác thực gửi đến {address}.</Text>
              ) : (
                <Field
                  label="Email"
                  value={email}
                  onChangeText={setEmail}
                  autoCapitalize="none"
                  autoComplete="email"
                  keyboardType="email-address"
                  error={error?.fieldErrors.email}
                />
              )}
              {verifying && (
                <Field
                  label="Mã OTP"
                  value={code}
                  onChangeText={(value) => setCode(value.replace(/\D/g, ''))}
                  keyboardType="number-pad"
                  autoComplete="one-time-code"
                  maxLength={6}
                  error={error?.fieldErrors.otp || error?.fieldErrors.code}
                />
              )}
              {register && !verifying && (
                <Field
                  label="Tên đăng nhập"
                  value={username}
                  onChangeText={setUsername}
                  autoCapitalize="none"
                  autoComplete="username"
                  error={error?.fieldErrors.username}
                />
              )}
              {register !== verifying && (
                <Field
                  label={register ? 'Mật khẩu' : 'Mật khẩu mới'}
                  value={password}
                  onChangeText={setPassword}
                  secureTextEntry
                  autoComplete="new-password"
                  hint="8–32 ký tự, có chữ hoa, số và ký tự đặc biệt."
                  error={error?.fieldErrors.password || error?.fieldErrors.newPassword}
                />
              )}
              {error && <Notice tone="danger">{error.message}</Notice>}
              {verifying ? (
                <>
                  <Button
                    title={register ? 'Xác thực email' : 'Đặt lại mật khẩu'}
                    onPress={confirm}
                    loading={busy}
                    disabled={code.length !== 6 || (!register && !password)}
                  />
                  <Button
                    title="Gửi lại mã"
                    variant="secondary"
                    disabled={busy}
                    onPress={() => run(() => requestCode(address))}
                  />
                </>
              ) : (
                <Button
                  title={register ? 'Tạo tài khoản' : 'Gửi mã đặt lại'}
                  onPress={start}
                  loading={busy}
                  disabled={!address || (register && (!username.trim() || !password))}
                />
              )}
            </>
          )}
          <Link href="/login" replace style={styles.link}>
            Về đăng nhập
          </Link>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  content: { flexGrow: 1, justifyContent: 'center', padding: spacing(6), gap: spacing(4) },
  title: { fontSize: 24, fontWeight: '800', color: colors.text },
  description: { color: colors.muted },
  link: { color: colors.primary, fontWeight: '600', textAlign: 'center', padding: spacing(2) },
})
