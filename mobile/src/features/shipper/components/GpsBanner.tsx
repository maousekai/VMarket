import { router, type Href } from 'expo-router'
import { Pressable, StyleSheet, View } from 'react-native'

import { AppText } from '@/components/ui'
import { colors, radius } from '@/theme'

import { useTrackerState } from '../location/useLocation'

export const LOCATION_ROUTE = '/(shipper)/location' as Href

/** Red banner shown while GPS sharing is interrupted; taps through to the "Vị trí" tab. */
export function GpsBanner() {
  const t = useTrackerState()
  if (t.status !== 'lost') return null
  return (
    <Pressable
      testID="shipper-gps-banner"
      accessibilityRole="button"
      onPress={() => router.navigate(LOCATION_ROUTE)}
      style={styles.banner}
    >
      <View style={styles.flex}>
        <AppText variant="label" color="danger">
          Mất kết nối GPS
        </AppText>
        <AppText variant="caption" color="text">
          Người mua chưa thấy vị trí mới của bạn. Chạm để kiểm tra.
        </AppText>
      </View>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  banner: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.dangerSoft,
    borderRadius: radius.md,
    padding: 12,
  },
  flex: { flex: 1, gap: 2 },
})
