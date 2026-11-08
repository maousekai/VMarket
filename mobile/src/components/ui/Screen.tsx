import type { ReactNode } from 'react'
import {
  KeyboardAvoidingView,
  Platform,
  RefreshControl,
  ScrollView,
  StyleSheet,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native'

import { AppHeader } from '@/components/ui/AppHeader'
import { colors, layout } from '@/theme'

export interface ScreenProps {
  title: string
  role?: 'buyer' | 'shipper'
  onBack?: () => void
  /** Header right slot. */
  right?: ReactNode
  /** false -> plain View body (use when the screen owns a FlatList / its own scroller). */
  scroll?: boolean
  /** Fixed footer above the tab bar (e.g. a primary CTA). */
  footer?: ReactNode
  refreshing?: boolean
  onRefresh?: () => void
  /** Override body padding/gap. */
  contentStyle?: StyleProp<ViewStyle>
  children?: ReactNode
  testID?: string
}

/**
 * Standard screen: AppHeader + keyboard-avoiding body (bg surfaceSubtle, padding 20, gap 12)
 * + optional fixed footer. Every feature screen should be wrapped in this.
 */
export function Screen({
  title,
  role = 'buyer',
  onBack,
  right,
  scroll = true,
  footer,
  refreshing,
  onRefresh,
  contentStyle,
  children,
  testID,
}: ScreenProps) {
  return (
    <View testID={testID} style={styles.root}>
      <AppHeader title={title} role={role} onBack={onBack} right={right} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {scroll ? (
          <ScrollView
            style={styles.flex}
            contentContainerStyle={[styles.content, contentStyle]}
            keyboardShouldPersistTaps="handled"
            refreshControl={
              onRefresh ? (
                <RefreshControl refreshing={!!refreshing} onRefresh={onRefresh} />
              ) : undefined
            }
          >
            {children}
          </ScrollView>
        ) : (
          <View style={[styles.flex, styles.content, contentStyle]}>{children}</View>
        )}
        {footer ? <View style={styles.footer}>{footer}</View> : null}
      </KeyboardAvoidingView>
    </View>
  )
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.surfaceSubtle },
  flex: { flex: 1 },
  content: {
    padding: layout.screenPaddingX,
    paddingTop: layout.screenPaddingTop,
    gap: layout.stackGap,
  },
  footer: {
    paddingHorizontal: layout.screenPaddingX,
    paddingVertical: 12,
    gap: 12,
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderTopColor: colors.border,
  },
})
