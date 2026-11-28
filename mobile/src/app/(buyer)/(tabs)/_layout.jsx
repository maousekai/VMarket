import { Tabs } from 'expo-router/js-tabs'
import { BottomNav } from '../../../components/ui'

// Thanh dieu huong 3 tab theo Figma: Trang chu / Gio hang / Tai khoan.
export default function BuyerTabs() {
  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(props) => <BottomNav {...props} />}>
      <Tabs.Screen name="index" options={{ title: 'Trang chủ' }} />
      <Tabs.Screen name="cart" options={{ title: 'Giỏ hàng' }} />
      <Tabs.Screen name="account" options={{ title: 'Tài khoản' }} />
    </Tabs>
  )
}
