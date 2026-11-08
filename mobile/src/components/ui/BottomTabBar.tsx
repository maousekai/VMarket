import { useRouter, usePathname, type Href } from 'expo-router'
import { useEffect, useState } from 'react'
import { Keyboard, Platform, Pressable, StyleSheet, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { AppText } from '@/components/ui/AppText'
import { colors, layout, radius } from '@/theme'

export interface TabItem {
  /** Where the tab goes, e.g. '/(buyer)/cart' ('/(buyer)' for the index tab). */
  route: Href
  label: string
  /**
   * URL paths (without group segments) that highlight this tab: '/cart', '/orders' ... A prefix
   * matches itself and everything below it ('/orders' matches '/orders/12'). '/' matches only '/'.
   * Include the tab's own path.
   */
  match: string[]
}

export interface BottomTabBarProps {
  items: TabItem[]
  testID?: string
}

function isActive(item: TabItem, pathname: string) {
  return item.match.some((p) =>
    p === '/' ? pathname === '/' : pathname === p || pathname.startsWith(`${p}/`),
  )
}

/**
 * Text-only bottom navigation that stays visible on EVERY screen of an area. It is rendered by the
 * area layout next to the <Stack> (not inside a Tabs navigator, see docs/FOUNDATION.md), and
 * highlights the owning tab from the current pathname through `match`.
 */
export function BottomTabBar({ items, testID }: BottomTabBarProps) {
  const insets = useSafeAreaInsets()
  const router = useRouter()
  const pathname = usePathname()
  const [keyboardUp, setKeyboardUp] = useState(false)

  useEffect(() => {
    if (Platform.OS !== 'android') return
    const a = Keyboard.addListener('keyboardDidShow', () => setKeyboardUp(true))
    const b = Keyboard.addListener('keyboardDidHide', () => setKeyboardUp(false))
    return () => {
      a.remove()
      b.remove()
    }
  }, [])

  if (keyboardUp) return null

  return (
    <View
      testID={testID ?? 'bottom-tab-bar'}
      style={[
        styles.root,
        { height: layout.tabBarHeight + insets.bottom, paddingBottom: insets.bottom },
      ]}
    >
      {items.map((item) => {
        const active = isActive(item, pathname)
        return (
          <Pressable
            key={item.label}
            testID={`tab-${item.label}`}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            onPress={() => router.navigate(item.route)}
            style={[styles.item, active && styles.itemActive]}
          >
            <AppText
              variant={active ? 'label' : 'body'}
              color={active ? 'primary700' : 'textSecondary'}
              style={active ? undefined : styles.inactiveText}
              numberOfLines={1}
            >
              {item.label}
            </AppText>
          </Pressable>
        )
      })}
    </View>
  )
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    paddingHorizontal: 16,
    paddingTop: 16,
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
  item: {
    flex: 1,
    height: layout.tabItemHeight,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  itemActive: { backgroundColor: colors.primary50 },
  inactiveText: { fontFamily: 'PlusJakartaSans_500Medium' },
})
