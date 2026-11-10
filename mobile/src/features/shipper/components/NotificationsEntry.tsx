import { router, type Href } from 'expo-router'
import { Pressable, StyleSheet, View } from 'react-native'

import { AppText } from '@/components/ui'
import { useUnreadCount } from '@/features/notifications/useNotifications'
import { colors, radius } from '@/theme'

/** Secondary-style, left-aligned "Thông báo" row (3.11) with the unread count at the right. */
export function NotificationsEntry() {
  const unread = useUnreadCount()
  return (
    <Pressable
      testID="shipper-notifications"
      accessibilityRole="button"
      onPress={() => router.push('/(shipper)/notifications' as Href)}
      style={({ pressed }) => [styles.row, pressed && { backgroundColor: colors.primary50 }]}
    >
      <AppText variant="button" color="primary600" style={styles.label}>
        Thông báo
      </AppText>
      {unread > 0 ? (
        <View style={styles.badge}>
          <AppText variant="caption" color="textInverse">
            {unread > 99 ? '99+' : unread}
          </AppText>
        </View>
      ) : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  row: {
    height: 48,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.primary600,
    backgroundColor: colors.surface,
  },
  label: { flex: 1, textAlign: 'left' },
  badge: {
    minWidth: 22,
    height: 22,
    paddingHorizontal: 6,
    borderRadius: 11,
    backgroundColor: colors.accent500,
    alignItems: 'center',
    justifyContent: 'center',
  },
})
