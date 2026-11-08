import { Pressable, StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { colors, radius } from '@/theme'

export interface QuantityStepperProps {
  value: number
  onChange: (next: number) => void
  min?: number
  max?: number
  disabled?: boolean
  style?: StyleProp<ViewStyle>
  testID?: string
}

/** − / value / + control, 32px high. Clamps to [min, max] (default 1..99). */
export function QuantityStepper({
  value,
  onChange,
  min = 1,
  max = 99,
  disabled,
  style,
  testID,
}: QuantityStepperProps) {
  const canDec = !disabled && value > min
  const canInc = !disabled && value < max
  return (
    <View testID={testID} style={[styles.root, disabled && { opacity: 0.5 }, style]}>
      <Pressable
        testID={testID ? `${testID}-dec` : undefined}
        accessibilityRole="button"
        accessibilityLabel="Giảm số lượng"
        disabled={!canDec}
        onPress={() => onChange(Math.max(min, value - 1))}
        style={styles.btn}
      >
        <AppText variant="h3" color={canDec ? 'primary600' : 'textSubtle'}>
          −
        </AppText>
      </Pressable>
      <AppText variant="label" style={styles.value}>
        {value}
      </AppText>
      <Pressable
        testID={testID ? `${testID}-inc` : undefined}
        accessibilityRole="button"
        accessibilityLabel="Tăng số lượng"
        disabled={!canInc}
        onPress={() => onChange(Math.min(max, value + 1))}
        style={styles.btn}
      >
        <AppText variant="h3" color={canInc ? 'primary600' : 'textSubtle'}>
          +
        </AppText>
      </Pressable>
    </View>
  )
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    height: 32,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.sm,
    backgroundColor: colors.surface,
  },
  btn: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center' },
  value: { minWidth: 32, textAlign: 'center' },
})
