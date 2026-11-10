import type { ReactNode } from 'react'
import { Pressable, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { colors, layout, radius } from '@/theme'

export interface CardProps {
  children?: ReactNode
  /** Makes the card pressable (pressed bg surfaceSubtle). */
  onPress?: () => void
  /** Remove inner padding (e.g. for edge-to-edge rows). */
  flush?: boolean
  disabled?: boolean
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** White card: 1px border, radius 10, padding 16, inner gap 12. */
export function Card({ children, onPress, flush, disabled, style, testID }: CardProps) {
  const base = [styles.card, flush && styles.flush, disabled && styles.disabled]
  if (onPress) {
    return (
      <Pressable
        testID={testID}
        accessibilityRole="button"
        disabled={disabled}
        onPress={onPress}
        style={({ pressed }) => [
          ...base,
          pressed && { backgroundColor: colors.surfaceSubtle },
          style,
        ]}
      >
        {children}
      </Pressable>
    )
  }
  return (
    <View testID={testID} style={[...base, style]}>
      {children}
    </View>
  )
}

export interface InfoCardProps {
  title: string
  body?: string
  children?: ReactNode
  onPress?: () => void
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** Card with a `label` title and `body` text (optionally extra children below). */
export function InfoCard({ title, body, children, onPress, style, testID }: InfoCardProps) {
  return (
    <Card testID={testID} onPress={onPress} style={style}>
      <AppText variant="label">{title}</AppText>
      {body ? (
        <AppText variant="body" color="textSecondary">
          {body}
        </AppText>
      ) : null}
      {children}
    </Card>
  )
}

/** 1px horizontal separator. */
export function Divider({ style, testID }: { style?: StyleProp<ViewStyle>; testID?: string }) {
  return <View testID={testID} style={[styles.divider, style]} />
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: layout.cardPadding,
    gap: layout.stackGap,
  },
  flush: { padding: 0, gap: 0, overflow: 'hidden' },
  disabled: { opacity: 0.5 },
  divider: { height: 1, backgroundColor: colors.border, alignSelf: 'stretch' },
})
