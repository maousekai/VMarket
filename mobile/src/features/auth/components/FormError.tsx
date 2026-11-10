import { StyleSheet, View } from 'react-native'

import { AppText } from '@/components/ui'
import { colors, radius } from '@/theme'

/** Form-level error banner (field errors are shown by `TextField` itself). */
export function FormError({ message, testID }: { message?: string | null; testID?: string }) {
  if (!message) return null
  return (
    <View testID={testID} accessibilityRole="alert" style={styles.box}>
      <AppText variant="caption" color="danger">
        {message}
      </AppText>
    </View>
  )
}

const styles = StyleSheet.create({
  box: {
    backgroundColor: colors.dangerSoft,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
})
