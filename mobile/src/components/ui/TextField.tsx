import { Ionicons } from '@expo/vector-icons'
import { useState, type ReactNode } from 'react'
import {
  Pressable,
  StyleSheet,
  TextInput,
  View,
  type KeyboardTypeOptions,
  type StyleProp,
  type TextInputProps,
  type ViewStyle,
} from 'react-native'

import { AppText } from '@/components/ui/AppText'
import { colors, fonts, layout, radius } from '@/theme'

export interface TextFieldProps {
  label?: string
  /** Appends ' *' to the label. */
  required?: boolean
  value?: string
  onChangeText?: (text: string) => void
  placeholder?: string
  /** Caption under the input (hidden while `error` is set). */
  helper?: string
  /** Red border + red caption. */
  error?: string | null
  disabled?: boolean
  /** Password field with an eye toggle. */
  secure?: boolean
  multiline?: boolean
  keyboardType?: KeyboardTypeOptions
  autoCapitalize?: TextInputProps['autoCapitalize']
  autoComplete?: TextInputProps['autoComplete']
  textContentType?: TextInputProps['textContentType']
  returnKeyType?: TextInputProps['returnKeyType']
  maxLength?: number
  onSubmitEditing?: () => void
  onBlur?: () => void
  autoFocus?: boolean
  /** Element rendered inside the field on the right (ignored when `secure`/`picker`). */
  right?: ReactNode
  /** Pressable field that shows value/placeholder + chevron; use with a BottomSheet. */
  picker?: boolean
  onPress?: () => void
  style?: StyleProp<ViewStyle>
  testID?: string
}

export function TextField({
  label,
  required,
  value,
  onChangeText,
  placeholder,
  helper,
  error,
  disabled,
  secure,
  multiline,
  keyboardType,
  autoCapitalize,
  autoComplete,
  textContentType,
  returnKeyType,
  maxLength,
  onSubmitEditing,
  onBlur,
  autoFocus,
  right,
  picker,
  onPress,
  style,
  testID,
}: TextFieldProps) {
  const [focused, setFocused] = useState(false)
  const [hidden, setHidden] = useState(true)

  const borderColor = error ? colors.danger : focused ? colors.primary600 : colors.border
  const boxStyle = [
    styles.box,
    multiline && styles.boxMulti,
    { borderColor, borderWidth: focused && !error ? 1.5 : 1 },
    disabled && styles.boxDisabled,
  ]

  let field: ReactNode
  if (picker) {
    field = (
      <Pressable
        testID={testID}
        accessibilityRole="button"
        accessibilityLabel={label}
        disabled={disabled}
        onPress={onPress}
        style={[...boxStyle, styles.row]}
      >
        <AppText
          variant="bodyLg"
          color={value ? 'text' : 'textSecondary'}
          numberOfLines={1}
          style={styles.flex}
        >
          {value || placeholder}
        </AppText>
        <Ionicons name="chevron-down" size={20} color={colors.textSubtle} />
      </Pressable>
    )
  } else {
    field = (
      <View style={[...boxStyle, styles.row]}>
        <TextInput
          testID={testID}
          value={value}
          onChangeText={onChangeText}
          placeholder={placeholder}
          placeholderTextColor={colors.textSecondary}
          editable={!disabled}
          secureTextEntry={secure && hidden}
          multiline={multiline}
          keyboardType={keyboardType}
          autoCapitalize={
            autoCapitalize ?? (secure || keyboardType === 'email-address' ? 'none' : undefined)
          }
          autoComplete={autoComplete}
          textContentType={textContentType}
          returnKeyType={returnKeyType}
          maxLength={maxLength}
          onSubmitEditing={onSubmitEditing}
          autoFocus={autoFocus}
          onFocus={() => setFocused(true)}
          onBlur={() => {
            setFocused(false)
            onBlur?.()
          }}
          textAlignVertical={multiline ? 'top' : 'center'}
          style={[styles.input, multiline && styles.inputMulti, disabled && styles.inputDisabled]}
        />
        {secure ? (
          <Pressable
            testID={testID ? `${testID}-toggle` : undefined}
            accessibilityRole="button"
            accessibilityLabel={hidden ? 'Hiện mật khẩu' : 'Ẩn mật khẩu'}
            hitSlop={10}
            onPress={() => setHidden((h) => !h)}
          >
            <Ionicons
              name={hidden ? 'eye-outline' : 'eye-off-outline'}
              size={20}
              color={colors.textSubtle}
            />
          </Pressable>
        ) : (
          right
        )}
      </View>
    )
  }

  return (
    <View style={[styles.root, style]}>
      {label ? (
        <AppText variant="label" style={styles.label}>
          {label + (required ? ' *' : '')}
        </AppText>
      ) : null}
      {field}
      {error ? (
        <AppText variant="caption" color="danger" style={styles.helper}>
          {error}
        </AppText>
      ) : helper ? (
        <AppText variant="caption" color="textSecondary" style={styles.helper}>
          {helper}
        </AppText>
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  root: { alignSelf: 'stretch' },
  label: { marginBottom: 12 },
  flex: { flex: 1 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  box: {
    minHeight: layout.controlHeight,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
    paddingHorizontal: 16,
  },
  boxMulti: { alignItems: 'flex-start', paddingVertical: 12 },
  boxDisabled: { backgroundColor: colors.surfaceMuted },
  input: {
    flex: 1,
    height: layout.controlHeight - 2,
    fontFamily: fonts.regular,
    fontSize: 16,
    color: colors.text,
    padding: 0,
  },
  inputMulti: { height: undefined, minHeight: 96 },
  inputDisabled: { color: colors.textSubtle },
  helper: { marginTop: 6 },
})
