import { useEffect, useState, type ReactNode } from 'react'
import {
  Animated,
  Easing,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
  useWindowDimensions,
} from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { AppText } from '@/components/ui/AppText'
import { Button } from '@/components/ui/Button'
import { colors, radius, shadow } from '@/theme'

export interface BottomSheetProps {
  visible: boolean
  onClose: () => void
  title?: string
  children?: ReactNode
  /** Wrap children in a ScrollView (long lists). Default true. */
  scrollable?: boolean
  testID?: string
}

/** Modal bottom sheet: scrim (tap to close), grab handle, title, safe-area padding. */
export function BottomSheet({
  visible,
  onClose,
  title,
  children,
  scrollable = true,
  testID,
}: BottomSheetProps) {
  const insets = useSafeAreaInsets()
  const { height } = useWindowDimensions()
  const anim = useState(() => new Animated.Value(0))[0]
  const [mounted, setMounted] = useState(visible)
  if (visible && !mounted) setMounted(true)

  useEffect(() => {
    if (visible) {
      Animated.timing(anim, {
        toValue: 1,
        duration: 220,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }).start()
    } else {
      Animated.timing(anim, {
        toValue: 0,
        duration: 180,
        easing: Easing.in(Easing.cubic),
        useNativeDriver: true,
      }).start(({ finished }) => {
        if (finished) setMounted(false)
      })
    }
  }, [visible, anim])

  const body = <View style={styles.content}>{children}</View>

  return (
    <Modal
      visible={mounted}
      transparent
      animationType="none"
      statusBarTranslucent
      onRequestClose={onClose}
    >
      <KeyboardAvoidingView
        style={styles.fill}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <Animated.View style={[styles.scrim, { opacity: anim }]}>
          <Pressable
            style={styles.fill}
            onPress={onClose}
            accessibilityLabel="Đóng"
            testID={testID ? `${testID}-scrim` : undefined}
          />
        </Animated.View>
        <Animated.View
          testID={testID}
          style={[
            styles.sheet,
            shadow.sheet,
            {
              maxHeight: height * 0.85,
              paddingBottom: 20 + insets.bottom,
              transform: [
                { translateY: anim.interpolate({ inputRange: [0, 1], outputRange: [height, 0] }) },
              ],
            },
          ]}
        >
          <View style={styles.handle} />
          {title ? (
            <AppText variant="h3" style={styles.title}>
              {title}
            </AppText>
          ) : null}
          {scrollable ? (
            <ScrollView keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator={false}>
              {body}
            </ScrollView>
          ) : (
            body
          )}
        </Animated.View>
      </KeyboardAvoidingView>
    </Modal>
  )
}

export interface ConfirmDialogProps {
  visible: boolean
  title: string
  message?: string
  confirmLabel?: string
  cancelLabel?: string
  /** Red confirm button. */
  destructive?: boolean
  loading?: boolean
  onConfirm: () => void
  onCancel: () => void
  testID?: string
}

/** Centered modal dialog with stacked confirm / cancel buttons. */
export function ConfirmDialog({
  visible,
  title,
  message,
  confirmLabel = 'Xác nhận',
  cancelLabel = 'Hủy',
  destructive,
  loading,
  onConfirm,
  onCancel,
  testID,
}: ConfirmDialogProps) {
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onCancel}
    >
      <View style={[styles.fill, styles.scrim, styles.center]}>
        <View testID={testID} style={styles.dialog}>
          <AppText variant="h3">{title}</AppText>
          {message ? (
            <AppText variant="body" color="textSecondary">
              {message}
            </AppText>
          ) : null}
          <Button
            testID={testID ? `${testID}-confirm` : undefined}
            title={confirmLabel}
            kind={destructive ? 'danger' : 'primary'}
            loading={loading}
            onPress={onConfirm}
          />
          <Button
            testID={testID ? `${testID}-cancel` : undefined}
            title={cancelLabel}
            kind="neutral"
            disabled={loading}
            onPress={onCancel}
          />
        </View>
      </View>
    </Modal>
  )
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  scrim: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: colors.overlay,
  },
  center: { alignItems: 'center', justifyContent: 'center', padding: 20 },
  sheet: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    paddingHorizontal: 20,
    paddingTop: 8,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: radius.full,
    backgroundColor: colors.border,
    marginBottom: 16,
  },
  title: { marginBottom: 12 },
  content: { gap: 12 },
  dialog: {
    alignSelf: 'stretch',
    maxWidth: 420,
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: 20,
    gap: 12,
  },
})
