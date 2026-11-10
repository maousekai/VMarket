import { Ionicons } from '@expo/vector-icons'
import type { ReactNode } from 'react'
import { Pressable, StyleSheet, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { AppText } from '@/components/ui/AppText'
import { LogoMark } from '@/components/ui/LogoMark'
import { colors, layout } from '@/theme'

export interface AppHeaderProps {
  /** Screen title (single line, h3 white). */
  title: string
  role?: 'buyer' | 'shipper'
  /** When set, a chevron-left back button is shown before the title. */
  onBack?: () => void
  /** Optional element aligned right of the title row. */
  right?: ReactNode
  testID?: string
}

/** Blue 120px header (+ status bar inset) with brand row, role tag and screen title. */
export function AppHeader({ title, role = 'buyer', onBack, right, testID }: AppHeaderProps) {
  const insets = useSafeAreaInsets()
  return (
    <View
      testID={testID}
      style={[styles.root, { height: layout.headerHeight + insets.top, paddingTop: insets.top }]}
    >
      <View style={styles.brandRow}>
        <LogoMark size={28} />
        <AppText variant="wordmark" color="textInverse" style={styles.wordmark}>
          VMarket
        </AppText>
        <View style={styles.flex} />
        <AppText variant="caption" color="textInverseMuted" style={styles.tag}>
          {role === 'shipper' ? 'SHIPPER' : 'MUA SẮM'}
        </AppText>
      </View>
      <View style={styles.titleRow}>
        {onBack ? (
          <Pressable
            testID={testID ? `${testID}-back` : 'header-back'}
            accessibilityRole="button"
            accessibilityLabel="Quay lại"
            onPress={onBack}
            hitSlop={10}
            style={styles.back}
          >
            <Ionicons name="chevron-back" size={24} color={colors.textInverse} />
          </Pressable>
        ) : null}
        <AppText variant="h3" color="textInverse" numberOfLines={1} style={styles.title}>
          {title}
        </AppText>
        {right}
      </View>
    </View>
  )
}

const styles = StyleSheet.create({
  root: { backgroundColor: colors.primary600, paddingHorizontal: layout.screenPaddingX },
  brandRow: { flexDirection: 'row', alignItems: 'center', height: 28, marginTop: 16, gap: 8 },
  wordmark: { marginLeft: 0 },
  flex: { flex: 1 },
  tag: { textTransform: 'uppercase' },
  titleRow: { flexDirection: 'row', alignItems: 'center', marginTop: 19, height: 26 },
  back: { width: 28, height: 44, justifyContent: 'center', marginLeft: -4 },
  title: { flex: 1 },
})
