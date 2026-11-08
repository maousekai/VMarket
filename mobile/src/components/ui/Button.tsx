import { Ionicons } from '@expo/vector-icons'
import type { ComponentProps } from 'react'
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  type StyleProp,
  type ViewStyle,
} from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { colors, layout, radius } from '@/theme'

export type IconName = ComponentProps<typeof Ionicons>['name']
export type ButtonKind = 'primary' | 'secondary' | 'accent' | 'neutral' | 'danger'

export interface ButtonProps {
  title: string
  onPress?: () => void
  kind?: ButtonKind
  /** L = 48px (default, mobile standard), M = 40px compact. */
  size?: 'L' | 'M'
  loading?: boolean
  disabled?: boolean
  fullWidth?: boolean
  /** Ionicons name shown before the label. */
  icon?: IconName
  style?: StyleProp<ViewStyle>
  testID?: string
}

const palette: Record<ButtonKind, { bg: string; pressed: string; fg: string; border?: string }> = {
  primary: { bg: colors.primary600, pressed: colors.primary700, fg: colors.textInverse },
  secondary: {
    bg: colors.surface,
    pressed: colors.primary50,
    fg: colors.primary600,
    border: colors.primary600,
  },
  accent: { bg: colors.accent500, pressed: colors.accent600, fg: colors.textInverse },
  neutral: { bg: colors.surfaceMuted, pressed: colors.border, fg: colors.textSecondary },
  danger: { bg: colors.danger, pressed: '#B91C1C', fg: colors.textInverse },
}

export function Button({
  title,
  onPress,
  kind = 'primary',
  size = 'L',
  loading = false,
  disabled = false,
  fullWidth = true,
  icon,
  style,
  testID,
}: ButtonProps) {
  const p = palette[kind]
  const inactive = disabled || loading
  const height = size === 'L' ? layout.controlHeight : layout.controlHeightM
  const fg = disabled ? colors.textSecondary : p.fg
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled: inactive, busy: loading }}
      disabled={inactive}
      onPress={onPress}
      style={({ pressed }) => [
        styles.base,
        {
          height,
          alignSelf: fullWidth ? 'stretch' : 'flex-start',
          backgroundColor: disabled ? colors.border : pressed ? p.pressed : p.bg,
          borderWidth: p.border ? 1 : 0,
          borderColor: p.border,
        },
        style,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={kind === 'secondary' ? colors.primary600 : fg} />
      ) : (
        <>
          {icon ? <Ionicons name={icon} size={size === 'L' ? 20 : 18} color={fg} /> : null}
          <AppText
            variant={size === 'L' ? 'button' : 'label'}
            style={{ color: fg }}
            numberOfLines={1}
          >
            {title}
          </AppText>
        </>
      )}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  base: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 20,
    borderRadius: radius.md,
  },
})
