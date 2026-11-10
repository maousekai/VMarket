import { Text, type TextProps } from 'react-native'

import { colors, type } from '@/theme'

export type TextVariant = keyof typeof type
export type TextColor = keyof typeof colors

export interface AppTextProps extends TextProps {
  /** Typography style from the design system (default `body`). */
  variant?: TextVariant
  /** Colour token (default `text`). */
  color?: TextColor
  testID?: string
}

/** Text with design-system typography. Use instead of RN `Text`. */
export function AppText({
  variant = 'body',
  color = 'text',
  style,
  testID,
  ...rest
}: AppTextProps) {
  return <Text testID={testID} style={[type[variant], { color: colors[color] }, style]} {...rest} />
}
