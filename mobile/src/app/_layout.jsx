// defineTask cua GPS nen phai dang ky o module scope, truoc khi app render.
import '../shipper/locationTracking'
import { useEffect } from 'react'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import * as SystemUI from 'expo-system-ui'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { isShipper } from '../auth/session'
import { Loading } from '../components/ui'
import { ThemeProvider, useTheme } from '../theme'

function RootNavigator() {
  const { status, session } = useAuth()
  const { colors } = useTheme()
  useEffect(() => {
    SystemUI.setBackgroundColorAsync(colors.background).catch(() => {})
  }, [colors.background])
  if (status === 'loading') return <Loading label="Đang khởi động…" />
  const signedIn = status === 'signedIn'
  const shipper = signedIn && isShipper(session)
  // Cung mot app: tai khoan SHIPPER vao phan he giao hang, con lai vao giao dien nguoi mua.
  return (
    <Stack
      screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.background } }}
    >
      <Stack.Protected guard={shipper}>
        <Stack.Screen name="(shipper)" />
      </Stack.Protected>
      <Stack.Protected guard={signedIn && !shipper}>
        <Stack.Screen name="(buyer)" />
      </Stack.Protected>
      <Stack.Protected guard={!signedIn}>
        <Stack.Screen name="login" />
        <Stack.Screen name="register" />
        <Stack.Screen name="forgot-password" />
      </Stack.Protected>
    </Stack>
  )
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <ThemeProvider>
        <AuthProvider>
          {/* Header luon xanh primary nen chu thanh trang thai mau sang o ca hai che do. */}
          <StatusBar style="light" />
          <RootNavigator />
        </AuthProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  )
}
