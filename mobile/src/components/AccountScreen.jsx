import { useState } from 'react'
import { StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useAuth } from '../auth/AuthProvider'
import { isShipper } from '../auth/session'
import { stopTracking } from '../shipper/locationTracking'
import { colors, spacing } from '../theme'
import { Button, Card } from './ui'

export default function AccountScreen() {
  const { session, logout } = useAuth()
  const [busy, setBusy] = useState(false)

  async function signOut() {
    setBusy(true)
    // Khong de GPS tiep tuc chay nen sau khi shipper dang xuat.
    if (isShipper(session)) await stopTracking().catch(() => {})
    await logout()
  }

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.container}>
        <Text style={styles.title} accessibilityRole="header">
          Tài khoản
        </Text>
        <Card>
          <Text style={styles.name}>{session?.username || session?.email}</Text>
          <Text style={styles.muted}>{session?.email}</Text>
          <Text style={styles.muted}>
            Vai trò: {isShipper(session) ? 'Người giao hàng' : 'Người mua'}
          </Text>
        </Card>
        <Button title="Đăng xuất" variant="secondary" loading={busy} onPress={signOut} />
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  container: { padding: spacing(4), gap: spacing(4) },
  title: { fontSize: 22, fontWeight: '800', color: colors.text },
  name: { fontSize: 18, fontWeight: '700', color: colors.text },
  muted: { color: colors.muted },
})
