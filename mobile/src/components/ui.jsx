import { forwardRef, useState } from 'react'
import {
  ActivityIndicator,
  Image,
  Pressable,
  RefreshControl,
  ScrollView,
  Text,
  TextInput,
  View,
} from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { makeStyles, radius, spacing, type, useTheme } from '../theme'
import bag from '../../assets/vmarket-bag.png'

// Bo component theo design system Figma "VMarket" (Button, Input, MobileHeader, Card, Bottom
// navigation). Mau lay tu theme hien tai (sang / toi).

/** Header 120px xanh primary-600, logo tui hang tren nen accent-500, nhan vai tro ben phai. */
export function MobileHeader({ title, role = 'buyer' }) {
  const styles = useStyles()
  const insets = useSafeAreaInsets()
  return (
    <View style={[styles.header, { paddingTop: insets.top + spacing(4) }]}>
      <View style={styles.brand}>
        <View style={styles.logo}>
          <Image source={bag} style={styles.logoImage} accessibilityIgnoresInvertColors />
        </View>
        <Text style={styles.brandName}>VMarket</Text>
        <Text style={styles.role}>{role === 'shipper' ? 'SHIPPER' : 'MUA SẮM'}</Text>
      </View>
      <Text style={styles.headerTitle} accessibilityRole="header" numberOfLines={2}>
        {title}
      </Text>
    </View>
  )
}

/**
 * Khung man hinh: header + vung cuon (nen surface-subtle, padding 20, khoang cach 12). `scroll`
 * false khi man tu quan ly danh sach (FlatList).
 */
export const Screen = forwardRef(function Screen(
  { title, role, children, scroll = true, refreshing, onRefresh, footer },
  ref,
) {
  const styles = useStyles()
  const { colors } = useTheme()
  return (
    <View style={styles.screen}>
      <MobileHeader title={title} role={role} />
      {scroll ? (
        <ScrollView
          ref={ref}
          style={styles.viewport}
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          refreshControl={
            onRefresh ? (
              <RefreshControl
                refreshing={Boolean(refreshing)}
                onRefresh={onRefresh}
                tintColor={colors.primary}
                colors={[colors.primary]}
              />
            ) : undefined
          }
        >
          {children}
        </ScrollView>
      ) : (
        <View style={styles.viewport}>{children}</View>
      )}
      {footer}
    </View>
  )
})

export function Button({ title, onPress, variant = 'primary', disabled, loading, style }) {
  const styles = useStyles()
  const { colors } = useTheme()
  const inactive = disabled || loading
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
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
        <ActivityIndicator
          color={variant === 'secondary' ? colors.primary : colors.onPrimary}
          accessibilityLabel={title}
        />
      ) : (
        <Text style={[styles.buttonText, styles[`buttonText_${variant}`]]}>{title}</Text>
      )}
    </Pressable>
  )
}

/** Input VMarket: nhan (Label) + o nhap 48px; `secure` them nut Hien / An. */
export function Field({ label, error, hint, style, secure, ...props }) {
  const styles = useStyles()
  const { colors } = useTheme()
  const [visible, setVisible] = useState(false)
  return (
    <View style={[styles.field, style]}>
      {label ? <Text style={styles.label}>{label}</Text> : null}
      <View style={styles.inputRow}>
        <TextInput
          accessibilityLabel={label || props.placeholder}
          placeholderTextColor={colors.subtle}
          secureTextEntry={secure ? !visible : props.secureTextEntry}
          style={[
            styles.input,
            error && styles.inputError,
            props.multiline && styles.multiline,
            secure && styles.inputWithToggle,
          ]}
          {...props}
        />
        {secure ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
            onPress={() => setVisible((value) => !value)}
            style={styles.toggle}
            hitSlop={8}
          >
            <Text style={styles.toggleText}>{visible ? 'Ẩn' : 'Hiện'}</Text>
          </Pressable>
        ) : null}
      </View>
      {error ? (
        <Text style={styles.error}>{error}</Text>
      ) : hint ? (
        <Text style={styles.hint}>{hint}</Text>
      ) : null}
    </View>
  )
}

/** O lua chon dang Input (vd. phan loai, dia chi, phuong thuc thanh toan). */
export function Choice({ label, value, onPress, selected, accessibilityLabel }) {
  const styles = useStyles()
  return (
    <Pressable
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityState={selected === undefined ? undefined : { selected }}
      accessibilityLabel={accessibilityLabel || value}
      onPress={onPress}
      style={[styles.input, styles.choice, selected && styles.choiceSelected]}
    >
      {label ? <Text style={styles.choiceLabel}>{label}</Text> : null}
      <Text style={[styles.choiceValue, selected && styles.choiceValueSelected]}>{value}</Text>
    </Pressable>
  )
}

/** Card "Content /...": vien border-default, bo 10, padding 16; tieu de Label + noi dung Body. */
export function Card({ title, children, style, onPress, accessibilityLabel }) {
  const styles = useStyles()
  const Body = onPress ? Pressable : View
  return (
    <Body
      style={[styles.card, style]}
      onPress={onPress}
      accessibilityRole={onPress ? 'button' : undefined}
      accessibilityLabel={accessibilityLabel}
    >
      {title ? <Text style={styles.cardTitle}>{title}</Text> : null}
      {children}
    </Body>
  )
}

/** Cac dong Body trong card. */
export function Lines({ lines, style }) {
  const styles = useStyles()
  const rows = lines.filter((line) => line !== null && line !== undefined && line !== false)
  if (!rows.length) return null
  return (
    <View>
      {rows.map((line, index) => (
        <Text key={index} style={[styles.body, style]}>
          {line}
        </Text>
      ))}
    </View>
  )
}

export function Heading({ children, level = 2 }) {
  const styles = useStyles()
  return (
    <Text style={level === 2 ? styles.h2 : styles.h3} accessibilityRole="header">
      {children}
    </Text>
  )
}

export function Label({ children }) {
  const styles = useStyles()
  return <Text style={styles.label}>{children}</Text>
}

export function Body({ children, muted, style }) {
  const styles = useStyles()
  return <Text style={[styles.body, muted && styles.muted, style]}>{children}</Text>
}

export function Caption({ children }) {
  const styles = useStyles()
  return <Text style={styles.caption}>{children}</Text>
}

/** Gia: H3 (hoac H2 khi `large`) mau primary-700. */
export function Price({ children, large }) {
  const styles = useStyles()
  return <Text style={[large ? styles.h2 : styles.h3, styles.price]}>{children}</Text>
}

export function Notice({ children, tone = 'info' }) {
  const styles = useStyles()
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
  const styles = useStyles()
  return (
    <View style={[styles.badge, styles[`notice_${tone}`]]}>
      <Text style={[styles.badgeText, styles[`noticeText_${tone}`]]}>{label}</Text>
    </View>
  )
}

export function Loading({ label = 'Đang tải…' }) {
  const styles = useStyles()
  const { colors } = useTheme()
  return (
    <View style={styles.loading}>
      <ActivityIndicator color={colors.primary} />
      <Text style={styles.hint}>{label}</Text>
    </View>
  )
}

/** Trang thai trong (vd. "Gio hang trong", "Khong co ket qua") theo cac frame Figma. */
export function EmptyState({ title, message, action }) {
  return (
    <Card title={title}>
      {message ? <Body>{message}</Body> : null}
      {action || null}
    </Card>
  )
}

/**
 * Bottom navigation 76px: 3 nut ngang nhau, nut dang chon nen primary-50 chu primary-700. Dung
 * lam `tabBar` cua Tabs (expo-router/js-tabs).
 */
export function BottomNav({ state, descriptors, navigateToTab, insets }) {
  const styles = useStyles()
  return (
    <View
      style={[styles.nav, { paddingBottom: spacing(4) + (insets?.bottom || 0) }]}
      accessibilityRole="tablist"
    >
      {state.routes.map((route, index) => {
        const options = descriptors[route.key]?.options || {}
        if (options.href === null) return null
        const active = state.index === index
        const label = options.title || route.name
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            accessibilityLabel={label}
            onPress={() => navigateToTab(route.key)}
            style={[styles.navItem, active && styles.navItemActive]}
          >
            <Text style={[styles.navText, active && styles.navTextActive]}>{label}</Text>
          </Pressable>
        )
      })}
    </View>
  )
}

const useStyles = makeStyles((colors) => ({
  screen: { flex: 1, backgroundColor: colors.background },
  header: {
    backgroundColor: colors.header,
    paddingHorizontal: spacing(5),
    paddingBottom: spacing(4),
    gap: spacing(2),
    minHeight: 120,
  },
  brand: { flexDirection: 'row', alignItems: 'center', gap: spacing(2), height: 28 },
  logo: {
    width: 28,
    height: 28,
    borderRadius: 6,
    backgroundColor: colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoImage: { width: 20, height: 20 },
  brandName: { flex: 1, color: colors.onHeader, fontSize: 18, fontWeight: '600', lineHeight: 24 },
  role: { color: colors.onHeader, opacity: 0.9, ...type.caption },
  headerTitle: { color: colors.onHeader, fontSize: 20, fontWeight: '600', lineHeight: 26 },
  viewport: { flex: 1, backgroundColor: colors.background },
  content: { padding: spacing(5), gap: spacing(3), paddingBottom: spacing(8) },
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
    borderColor: colors.primary,
  },
  button_danger: { backgroundColor: colors.danger },
  buttonDisabled: { opacity: 0.5 },
  buttonPressed: { opacity: 0.85 },
  buttonText: { ...type.button, textAlign: 'center' },
  buttonText_primary: { color: colors.onPrimary },
  buttonText_secondary: { color: colors.primaryText },
  buttonText_danger: { color: colors.scheme === 'dark' ? colors.background : colors.onPrimary },
  field: { gap: spacing(1.5) },
  label: { ...type.label, color: colors.text },
  inputRow: { justifyContent: 'center' },
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: spacing(4),
    fontSize: 14,
    color: colors.text,
    backgroundColor: colors.surface,
  },
  inputWithToggle: { paddingRight: 64 },
  toggle: { position: 'absolute', right: spacing(4), paddingVertical: spacing(2) },
  toggleText: { ...type.label, color: colors.primaryText },
  multiline: { minHeight: 96, paddingTop: spacing(3), textAlignVertical: 'top' },
  inputError: { borderColor: colors.danger },
  error: { color: colors.danger, ...type.caption },
  hint: { color: colors.muted, ...type.caption },
  choice: { justifyContent: 'center', paddingVertical: spacing(2) },
  choiceSelected: { borderColor: colors.primary, backgroundColor: colors.primarySoft },
  choiceLabel: { ...type.caption, color: colors.muted },
  choiceValue: { ...type.body, color: colors.muted },
  choiceValueSelected: { color: colors.text, fontWeight: '600' },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing(4),
    gap: spacing(3),
  },
  cardTitle: { ...type.label, color: colors.text },
  h2: { ...type.h2, color: colors.text },
  h3: { ...type.h3, color: colors.text },
  body: { ...type.body, color: colors.text },
  muted: { color: colors.muted },
  caption: { ...type.caption, color: colors.muted },
  price: { color: colors.primaryText },
  notice: { borderRadius: radius.md, padding: spacing(3) },
  notice_info: { backgroundColor: colors.primarySoft },
  notice_danger: { backgroundColor: colors.dangerSoft },
  notice_success: { backgroundColor: colors.successSoft },
  notice_warning: { backgroundColor: colors.warningSoft },
  noticeText: { ...type.body },
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
  loading: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing(2),
    padding: spacing(6),
  },
  nav: {
    flexDirection: 'row',
    gap: spacing(2),
    paddingTop: spacing(4),
    paddingHorizontal: spacing(4),
    backgroundColor: colors.surface,
    borderTopWidth: 1,
    borderColor: colors.border,
  },
  navItem: {
    flex: 1,
    height: 44,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: 'transparent',
  },
  navItemActive: { backgroundColor: colors.primarySoft, borderColor: colors.primarySoft },
  navText: { ...type.label, color: colors.muted },
  navTextActive: { color: colors.primaryText },
}))
