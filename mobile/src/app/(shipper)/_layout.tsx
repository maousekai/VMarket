import { Redirect, Stack } from 'expo-router'
import { View } from 'react-native'

import { useAuth } from '@/auth/AuthProvider'
import { BottomTabBar } from '@/components/ui'
import { SHIPPER_TABS } from '@/navigation/tabs'

export const unstable_settings = { initialRouteName: 'index' }

/** Shipper area: requires role SHIPPER. Same Stack + tab bar convention as (buyer). */
export default function ShipperLayout() {
  const { status, isShipper } = useAuth()
  if (status !== 'authenticated') return <Redirect href="/(auth)/login" />
  if (!isShipper) return <Redirect href="/(buyer)" />
  return (
    <View style={{ flex: 1 }}>
      <Stack screenOptions={{ headerShown: false }} />
      <BottomTabBar items={SHIPPER_TABS} />
    </View>
  )
}
