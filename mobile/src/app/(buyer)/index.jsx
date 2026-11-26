import { StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useAuth } from '../../auth/AuthProvider'
import { Card } from '../../components/ui'
import { colors, spacing } from '../../theme'

export default function BuyerHome() {
  const { session } = useAuth()
  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.container}>
        <Text style={styles.title} accessibilityRole="header">
          Xin chào {session?.username || ''}
        </Text>
        <Card>
          <Text style={styles.text}>
            Trang chủ, tìm kiếm, giỏ hàng, đặt hàng và theo dõi đơn đang được hoàn thiện cho ứng
            dụng di động. Trong lúc chờ, bạn có thể mua sắm trên web VMarket bằng cùng tài khoản.
          </Text>
        </Card>
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  container: { padding: spacing(4), gap: spacing(4) },
  title: { fontSize: 22, fontWeight: '800', color: colors.text },
  text: { color: colors.muted, fontSize: 15, lineHeight: 22 },
})
