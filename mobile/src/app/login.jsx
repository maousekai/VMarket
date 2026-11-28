import { useState } from 'react'
import { Link } from 'expo-router'
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { getApiError } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { Button, Field, Notice } from '../components/ui'
import { colors, spacing } from '../theme'

export default function LoginScreen() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordVisible, setPasswordVisible] = useState(false)
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
    <SafeAreaView style={styles.safe}>
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
          <View style={styles.brand}>
            <Text style={styles.logo}>VMarket</Text>
            <Text style={styles.subtitle}>Đăng nhập để mua sắm hoặc nhận đơn giao hàng.</Text>
          </View>
          <Field
            label="Email"
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
            secureTextEntry={!passwordVisible}
            autoComplete="password"
            textContentType="password"
            error={error?.fieldErrors?.password}
            onSubmitEditing={ready ? submit : undefined}
          />
          <Button
            title={passwordVisible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
            variant="secondary"
            onPress={() => setPasswordVisible((visible) => !visible)}
          />
          {error && !Object.keys(error.fieldErrors).length && (
            <Notice tone="danger">{error.message}</Notice>
          )}
          <Button title="Đăng nhập" onPress={submit} loading={busy} disabled={!ready} />
          <View style={styles.links}>
            <Link href="/forgot-password" style={styles.link}>
              Quên mật khẩu?
            </Link>
            <Link href="/register" style={styles.link}>
              Đăng ký người mua
            </Link>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  flex: { flex: 1 },
  container: { flexGrow: 1, justifyContent: 'center', padding: spacing(6), gap: spacing(4) },
  brand: { gap: spacing(2), marginBottom: spacing(4) },
  logo: { fontSize: 32, fontWeight: '800', color: colors.primary },
  subtitle: { fontSize: 15, color: colors.muted },
  links: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    flexWrap: 'wrap',
    gap: spacing(2),
  },
  link: { color: colors.primary, fontWeight: '600', paddingVertical: spacing(2) },
})
