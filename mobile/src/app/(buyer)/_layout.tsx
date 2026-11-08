import { Redirect, Stack } from 'expo-router'
import { View } from 'react-native'

import { useAuth } from '@/auth/AuthProvider'
import { BottomTabBar } from '@/components/ui'
import { BUYER_TABS } from '@/navigation/tabs'

// Deep links (e.g. /orders/5) get the home screen underneath so "back" works.
export const unstable_settings = { initialRouteName: 'index' }

/**
 * Buyer area. Guests are allowed (use useRequireAuth() on protected actions).
 * A Stack (every file under (buyer)/ is a screen automatically) with the tab bar rendered below
 * it, so the bottom nav is visible on every screen. See docs/FOUNDATION.md.
 */
export default function BuyerLayout() {
  const { isShipper } = useAuth()
  if (isShipper) return <Redirect href="/(shipper)" />
  return (
    <View style={{ flex: 1 }}>
      <Stack screenOptions={{ headerShown: false }} />
      <BottomTabBar items={BUYER_TABS} />
    </View>
  )
}
