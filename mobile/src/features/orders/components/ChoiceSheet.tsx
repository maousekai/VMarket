import { Ionicons } from '@expo/vector-icons'
import { Pressable, StyleSheet } from 'react-native'

import { AppText, BottomSheet } from '@/components/ui'
import { colors } from '@/theme'

export interface Choice {
  key: string
  label: string
}

interface Props {
  visible: boolean
  title: string
  options: Choice[]
  selected: string | null
  onSelect: (key: string) => void
  onClose: () => void
  testID?: string
}

/** Bottom-sheet single choice list used by the picker-style fields. */
export function ChoiceSheet({
  visible,
  title,
  options,
  selected,
  onSelect,
  onClose,
  testID,
}: Props) {
  return (
    <BottomSheet visible={visible} title={title} onClose={onClose} testID={testID}>
      {options.map((o) => (
        <Pressable
          key={o.key}
          testID={testID ? `${testID}-${o.key}` : undefined}
          accessibilityRole="button"
          accessibilityState={{ selected: o.key === selected }}
          onPress={() => {
            onSelect(o.key)
            onClose()
          }}
          style={styles.row}
        >
          <AppText style={styles.label} color={o.key === selected ? 'primary700' : 'text'}>
            {o.label}
          </AppText>
          {o.key === selected ? (
            <Ionicons name="checkmark" size={20} color={colors.primary600} />
          ) : null}
        </Pressable>
      ))}
    </BottomSheet>
  )
}

const styles = StyleSheet.create({
  row: {
    minHeight: 44,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 8,
  },
  label: { flex: 1 },
})
