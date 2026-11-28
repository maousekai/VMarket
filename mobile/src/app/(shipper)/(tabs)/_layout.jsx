import { Tabs } from 'expo-router/js-tabs'
import { BottomNav } from '../../../components/ui'

// Thanh dieu huong 3 tab theo Figma: Don giao / Vi tri / Lich su.
export default function ShipperTabs() {
  return (
    <Tabs screenOptions={{ headerShown: false }} tabBar={(props) => <BottomNav {...props} />}>
      <Tabs.Screen name="index" options={{ title: 'Đơn giao' }} />
      <Tabs.Screen name="location" options={{ title: 'Vị trí' }} />
      <Tabs.Screen name="history" options={{ title: 'Lịch sử' }} />
    </Tabs>
  )
}
