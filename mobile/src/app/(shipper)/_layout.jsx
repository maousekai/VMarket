import { Tabs } from 'expo-router'
import { colors } from '../../theme'

// Phan he Shipper hien trong cung app khi tai khoan co vai tro SHIPPER (khong lam app rieng).
export default function ShipperTabs() {
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.primary,
        tabBarIcon: () => null,
        tabBarLabelStyle: { fontSize: 14, fontWeight: '600' },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Đơn giao' }} />
      <Tabs.Screen name="account" options={{ title: 'Tài khoản' }} />
    </Tabs>
  )
}
