import { Ionicons } from '@expo/vector-icons'
import { Pressable, StyleSheet, View } from 'react-native'

import { AppText, BottomSheet } from '@/components/ui'
import { colors } from '@/theme'

export interface PickerGroup<T extends string> {
  label?: string
  options: { value: T; label: string }[]
}

interface Props<T extends string> {
  visible: boolean
  title: string
  groups: PickerGroup<T>[]
  value: T | null | undefined
  onSelect: (value: T) => void
  onClose: () => void
  testID?: string
}

/** Option list inside a BottomSheet, for picker-style form fields. */
export function PickerSheet<T extends string>({
  visible,
  title,
  groups,
  value,
  onSelect,
  onClose,
  testID,
}: Props<T>) {
  return (
    <BottomSheet visible={visible} onClose={onClose} title={title} testID={testID}>
      {groups.map((group, index) => (
        <View key={group.label ?? index}>
          {group.label ? (
            <AppText variant="caption" color="textSecondary" style={styles.group}>
              {group.label}
            </AppText>
          ) : null}
          {group.options.map((option) => {
            const selected = option.value === value
            return (
              <Pressable
                key={option.value}
                testID={testID ? `${testID}-${option.value}` : undefined}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                onPress={() => {
                  onSelect(option.value)
                  onClose()
                }}
                style={({ pressed }) => [styles.row, pressed && styles.pressed]}
              >
                <AppText
                  variant="bodyLg"
                  color={selected ? 'primary600' : 'text'}
                  style={styles.flex}
                >
                  {option.label}
                </AppText>
                {selected ? (
                  <Ionicons name="checkmark" size={20} color={colors.primary600} />
                ) : null}
              </Pressable>
            )
          })}
        </View>
      ))}
    </BottomSheet>
  )
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  group: { marginTop: 8, marginBottom: 4 },
  row: { minHeight: 48, flexDirection: 'row', alignItems: 'center', gap: 8 },
  pressed: { backgroundColor: colors.surfaceSubtle },
})
