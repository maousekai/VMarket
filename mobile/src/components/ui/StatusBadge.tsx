import { Pressable, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { colors, radius, toneColors, type Tone } from '@/theme'

export interface StatusBadgeProps {
  label: string
  tone: Tone
  /** Compact = 22px high (lists), default 28px. */
  compact?: boolean
  /** Hide the 6px leading dot. */
  noDot?: boolean
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** Pill with a 6px dot + label in the tone colour on its soft background. */
export function StatusBadge({ label, tone, compact, noDot, style, testID }: StatusBadgeProps) {
  const c = toneColors(tone)
  return (
    <View
      testID={testID}
      style={[styles.badge, { backgroundColor: c.bg, height: compact ? 22 : 28 }, style]}
    >
      {noDot ? null : <View style={[styles.dot, { backgroundColor: c.fg }]} />}
      <AppText variant="caption" style={{ color: c.fg }} numberOfLines={1}>
        {label}
      </AppText>
    </View>
  )
}

export interface ChipProps {
  label: string
  selected?: boolean
  onPress?: () => void
  disabled?: boolean
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** Selectable filter chip (32px, pill). */
export function Chip({ label, selected, onPress, disabled, style, testID }: ChipProps) {
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ selected: !!selected, disabled }}
      disabled={disabled}
      onPress={onPress}
      style={[styles.chip, selected && styles.chipSelected, disabled && { opacity: 0.5 }, style]}
    >
      <AppText
        variant={selected ? 'label' : 'caption'}
        color={selected ? 'primary700' : 'textSecondary'}
      >
        {label}
      </AppText>
    </Pressable>
  )
}

export interface RibbonProps {
  label: string
  tone?: 'accent' | 'danger' | 'success'
  style?: StyleProp<ViewStyle>
  testID?: string
}

const ribbonBg = { accent: colors.accent500, danger: colors.danger, success: colors.success }

/** Small solid label for product cards ("Bán chạy", "Flash Sale", "Giao 2h"). */
export function Ribbon({ label, tone = 'accent', style, testID }: RibbonProps) {
  return (
    <View testID={testID} style={[styles.ribbon, { backgroundColor: ribbonBg[tone] }, style]}>
      <AppText variant="caption" color="textInverse">
        {label}
      </AppText>
    </View>
  )
}

const styles = StyleSheet.create({
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: 6,
    paddingHorizontal: 10,
    borderRadius: radius.full,
  },
  dot: { width: 6, height: 6, borderRadius: 3 },
  chip: {
    height: 32,
    paddingHorizontal: 12,
    borderRadius: radius.full,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chipSelected: { backgroundColor: colors.primary50, borderColor: colors.primary600 },
  ribbon: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.sm },
})
