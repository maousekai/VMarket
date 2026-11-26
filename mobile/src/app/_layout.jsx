// defineTask cua GPS nen phai dang ky o module scope, truoc khi app render.
import '../shipper/locationTracking'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import { SafeAreaProvider } from 'react-native-safe-area-context'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { isShipper } from '../auth/session'
import { Loading } from '../components/ui'

function RootNavigator() {
  const { status, session } = useAuth()
  if (status === 'loading') return <Loading label="Đang khởi động…" />
  const signedIn = status === 'signedIn'
  const shipper = signedIn && isShipper(session)
  // Cung mot app: tai khoan SHIPPER vao phan he giao hang, con lai vao giao dien nguoi mua.
  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Protected guard={shipper}>
        <Stack.Screen name="(shipper)" />
      </Stack.Protected>
      <Stack.Protected guard={signedIn && !shipper}>
        <Stack.Screen name="(buyer)" />
      </Stack.Protected>
      <Stack.Protected guard={!signedIn}>
        <Stack.Screen name="login" />
      </Stack.Protected>
    </Stack>
  )
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AuthProvider>
        <StatusBar style="dark" />
        <RootNavigator />
      </AuthProvider>
    </SafeAreaProvider>
  )
}
