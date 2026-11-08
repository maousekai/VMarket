import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { Animated, StyleSheet, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

import { AppText } from '@/components/ui/AppText'
import { colors, layout, radius } from '@/theme'

export type ToastType = 'info' | 'success' | 'error'

export interface ToastOptions {
  type?: ToastType
  message: string
  /** ms, default 3000 */
  duration?: number
}

interface ToastContextValue {
  show: (options: ToastOptions) => void
}

const ToastContext = createContext<ToastContextValue | null>(null)

interface ToastItem extends ToastOptions {
  id: number
}

function ToastView({ item, onDone }: { item: ToastItem; onDone: (id: number) => void }) {
  const opacity = useState(() => new Animated.Value(0))[0]
  useEffect(() => {
    Animated.timing(opacity, { toValue: 1, duration: 160, useNativeDriver: true }).start()
    const t = setTimeout(() => {
      Animated.timing(opacity, { toValue: 0, duration: 200, useNativeDriver: true }).start(() =>
        onDone(item.id),
      )
    }, item.duration ?? 3000)
    return () => clearTimeout(t)
  }, [item, opacity, onDone])

  const bar =
    item.type === 'success' ? colors.success : item.type === 'error' ? colors.danger : null
  return (
    <Animated.View style={[styles.toast, { opacity }]} accessibilityLiveRegion="polite">
      {bar ? <View style={[styles.bar, { backgroundColor: bar }]} /> : null}
      <AppText variant="body" color="textInverse" style={styles.text}>
        {item.message}
      </AppText>
    </Animated.View>
  )
}

/** Mount once near the root (already done in src/app/_layout.tsx). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const insets = useSafeAreaInsets()
  const [items, setItems] = useState<ToastItem[]>([])
  const counter = useRef(0)

  const show = useCallback((options: ToastOptions) => {
    counter.current += 1
    setItems((prev) => [...prev.slice(-2), { ...options, id: counter.current }])
  }, [])
  const remove = useCallback((id: number) => setItems((p) => p.filter((i) => i.id !== id)), [])
  const value = useMemo(() => ({ show }), [show])

  return (
    <ToastContext.Provider value={value}>
      {children}
      <View
        pointerEvents="none"
        style={[styles.host, { bottom: layout.tabBarHeight + insets.bottom + 8 }]}
      >
        {items.map((item) => (
          <ToastView key={item.id} item={item} onDone={remove} />
        ))}
      </View>
    </ToastContext.Provider>
  )
}

/** `const toast = useToast(); toast.show({ type: 'success', message: 'Đã lưu' })` */
export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>')
  return ctx
}

const styles = StyleSheet.create({
  host: { position: 'absolute', left: 20, right: 20, gap: 8 },
  toast: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.text,
    borderRadius: radius.md,
    overflow: 'hidden',
    paddingVertical: 12,
    paddingHorizontal: 16,
    gap: 12,
  },
  bar: { position: 'absolute', left: 0, top: 0, bottom: 0, width: 4 },
  text: { flex: 1 },
})
