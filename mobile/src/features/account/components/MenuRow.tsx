import { Ionicons } from '@expo/vector-icons'
import { StyleSheet } from 'react-native'

import { AppText, Card } from '@/components/ui'
import { colors } from '@/theme'

/** Pressable row of the account menu: label on the left, chevron on the right. */
export function MenuRow({
  label,
  onPress,
  testID,
}: {
  label: string
  onPress: () => void
  testID?: string
}) {
  return (
    <Card testID={testID} onPress={onPress} style={styles.row}>
      <AppText variant="label" style={styles.label}>
        {label}
      </AppText>
      <Ionicons name="chevron-forward" size={20} color={colors.textSubtle} />
    </Card>
  )
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, paddingVertical: 12 },
  label: { flex: 1 },
})
