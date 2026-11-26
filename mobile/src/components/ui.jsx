import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native'
import { colors, radius, spacing } from '../theme'

export function Button({ title, onPress, variant = 'primary', disabled, loading, style }) {
  const inactive = disabled || loading
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(inactive), busy: Boolean(loading) }}
      disabled={Boolean(inactive)}
      onPress={inactive ? undefined : onPress}
      style={({ pressed }) => [
        styles.button,
        styles[`button_${variant}`],
        inactive && styles.buttonDisabled,
        pressed && !inactive && styles.buttonPressed,
        style,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={variant === 'primary' ? '#fff' : colors.primary} />
      ) : (
        <Text style={[styles.buttonText, styles[`buttonText_${variant}`]]}>{title}</Text>
      )}
    </Pressable>
  )
}

export function Field({ label, error, hint, style, ...props }) {
  return (
    <View style={[styles.field, style]}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        placeholderTextColor={colors.subtle}
        style={[styles.input, error && styles.inputError, props.multiline && styles.multiline]}
        {...props}
      />
      {error ? (
        <Text style={styles.error}>{error}</Text>
      ) : hint ? (
        <Text style={styles.hint}>{hint}</Text>
      ) : null}
    </View>
  )
}

export function Card({ children, style }) {
  return <View style={[styles.card, style]}>{children}</View>
}

export function Notice({ children, tone = 'info' }) {
  return (
    <View
      accessibilityRole={tone === 'danger' ? 'alert' : undefined}
      style={[styles.notice, styles[`notice_${tone}`]]}
    >
      <Text style={[styles.noticeText, styles[`noticeText_${tone}`]]}>{children}</Text>
    </View>
  )
}

export function Badge({ label, tone = 'info' }) {
  return (
    <View style={[styles.badge, styles[`notice_${tone}`]]}>
      <Text style={[styles.badgeText, styles[`noticeText_${tone}`]]}>{label}</Text>
    </View>
  )
}

export function Loading({ label = 'Đang tải…' }) {
  return (
    <View style={styles.loading}>
      <ActivityIndicator color={colors.primary} />
      <Text style={styles.hint}>{label}</Text>
    </View>
  )
}

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    paddingHorizontal: spacing(4),
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  button_primary: { backgroundColor: colors.primary },
  button_secondary: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
  },
  button_danger: { backgroundColor: colors.danger },
  buttonDisabled: { opacity: 0.5 },
  buttonPressed: { opacity: 0.85 },
  buttonText: { fontSize: 16, fontWeight: '600' },
  buttonText_primary: { color: '#fff' },
  buttonText_secondary: { color: colors.text },
  buttonText_danger: { color: '#fff' },
  field: { gap: spacing(1.5) },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: spacing(4),
    fontSize: 16,
    color: colors.text,
    backgroundColor: colors.surface,
  },
  multiline: { minHeight: 96, paddingTop: spacing(3), textAlignVertical: 'top' },
  inputError: { borderColor: colors.danger },
  error: { color: colors.danger, fontSize: 13 },
  hint: { color: colors.subtle, fontSize: 13 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing(4),
    gap: spacing(3),
  },
  notice: { borderRadius: radius.md, padding: spacing(3) },
  notice_info: { backgroundColor: colors.primarySoft },
  notice_danger: { backgroundColor: colors.dangerSoft },
  notice_success: { backgroundColor: colors.successSoft },
  notice_warning: { backgroundColor: colors.warningSoft },
  noticeText: { fontSize: 14 },
  noticeText_info: { color: colors.primaryDark },
  noticeText_danger: { color: colors.danger },
  noticeText_success: { color: colors.success },
  noticeText_warning: { color: colors.warning },
  badge: {
    alignSelf: 'flex-start',
    borderRadius: radius.full,
    paddingHorizontal: spacing(2.5),
    paddingVertical: spacing(1),
  },
  badgeText: { fontSize: 12, fontWeight: '700' },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: spacing(2) },
})
