import { Tabs } from 'expo-router'
import { colors } from '../../theme'

export default function BuyerTabs() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.primary,
        tabBarIcon: () => null,
        tabBarLabelStyle: { fontSize: 14, fontWeight: '600' },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Trang chủ' }} />
      <Tabs.Screen name="orders" options={{ title: 'Đơn của tôi' }} />
      <Tabs.Screen name="delivery/[orderId]" options={{ href: null }} />
      <Tabs.Screen name="notifications" options={{ title: 'Thông báo' }} />
      <Tabs.Screen name="account" options={{ title: 'Tài khoản' }} />
    </Tabs>
  )
}
