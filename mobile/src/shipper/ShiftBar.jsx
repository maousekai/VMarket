import { useCallback, useEffect, useState } from 'react'
import { Alert, StyleSheet, Text, View } from 'react-native'
import { getApiError } from '../api/client'
import { endShift, getShift, startShift } from '../api/deliveryApi'
import { Button, Notice } from '../components/ui'
import { colors, radius, spacing } from '../theme'

const timeFormat = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit' })

/**
 * Nut "Bat dau ca" / "Ket thuc ca" o dau man "Don giao" (review PR 68 lan 3, phan 2). Chi shipper
 * dang trong ca moi duoc tu dong giao don; vao ca KHONG bat GPS.
 */
export default function ShiftBar({ revision, onChanged }) {
  const [shift, setShift] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let current = true
    getShift()
      .then((data) => current && setShift(data))
      .catch((failure) => current && setError(getApiError(failure).message))
    return () => {
      current = false
    }
  }, [revision])

  const run = useCallback(
    async (action) => {
      setBusy(true)
      setError('')
      try {
        setShift(await action())
        onChanged?.()
      } catch (failure) {
        const apiError = getApiError(failure)
        if (apiError.code === 'SHIFT_END_CONFIRM_REQUIRED') {
          Alert.alert('Kết thúc ca?', `${apiError.message}. Bạn vẫn muốn kết thúc ca?`, [
            { text: 'Ở lại ca', style: 'cancel' },
            {
              text: 'Kết thúc ca',
              style: 'destructive',
              onPress: () => run(() => endShift({ releaseUnpicked: true })),
            },
          ])
        } else setError(apiError.message)
      } finally {
        setBusy(false)
      }
    },
    [onChanged],
  )

  if (!shift && !error) return null
  const onShift = Boolean(shift?.onShift)
  return (
    <View style={[styles.bar, onShift ? styles.on : styles.off]} accessibilityLabel="Ca làm việc">
      <View style={styles.text}>
        <Text style={styles.title}>{onShift ? 'Bạn đang trong ca' : 'Bạn đang ngoài ca'}</Text>
        <Text style={styles.subtitle}>
          {onShift
            ? `Đang nhận đơn mới${shift.shiftChangedAt ? ` · vào ca lúc ${timeFormat.format(new Date(shift.shiftChangedAt))}` : ''}`
            : 'Bạn đang ngoài ca, sẽ không được giao đơn mới.'}
        </Text>
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </View>
      {shift ? (
        <Button
          title={onShift ? 'Kết thúc ca' : 'Bắt đầu ca'}
          variant={onShift ? 'secondary' : 'primary'}
          loading={busy}
          disabled={busy}
          onPress={() => run(onShift ? () => endShift() : startShift)}
        />
      ) : null}
    </View>
  )
}

const styles = StyleSheet.create({
  bar: {
    marginHorizontal: spacing(4),
    marginBottom: spacing(2),
    padding: spacing(4),
    borderRadius: radius.md,
    borderWidth: 1,
    gap: spacing(2),
  },
  on: { backgroundColor: colors.successSoft, borderColor: colors.success },
  off: { backgroundColor: colors.warningSoft, borderColor: colors.warning },
  text: { gap: 2 },
  title: { fontWeight: '700', color: colors.text },
  subtitle: { color: colors.muted },
})
