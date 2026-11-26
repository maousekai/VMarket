import { Tabs } from 'expo-router'
import { colors } from '../../theme'

// Cac tab nguoi mua (trang chu, tim kiem, gio hang, don hang, thong bao) bo sung o PR tiep theo
// cua PBL6-72; PR nen tang chi co khung dieu huong va tai khoan.
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
      <Tabs.Screen name="account" options={{ title: 'Tài khoản' }} />
    </Tabs>
  )
}
