import { useState } from 'react'
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { Button, Field, Notice } from '../components/ui'
import { formatMoney } from '../api/deliveryApi'
import { FAILURE_REASONS, MAX_ATTEMPTS } from '../deliveryStatus'
import { colors, radius, spacing } from '../theme'

function Sheet({ title, description, onClose, children }) {
  return (
    <Modal transparent animationType="slide" visible onRequestClose={onClose}>
      <KeyboardAvoidingView
        style={styles.backdrop}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View style={styles.sheet} accessibilityViewIsModal>
          {/* handled: bam nut khi ban phim dang mo thi nut nhan luon, khong chi dong ban phim. */}
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
            <Text style={styles.title} accessibilityRole="header">
              {title}
            </Text>
            {description ? <Text style={styles.description}>{description}</Text> : null}
            {children}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  )
}

/** Xac nhan giao thanh cong: don COD phai nhap dung so tien da thu (COD_AMOUNT_MISMATCH). */
export function DeliverSheet({ assignment, busy, error, onClose, onSubmit }) {
  const cod = Number(assignment.codAmount || 0)
  const prepaid = cod === 0
  const [amount, setAmount] = useState('')
  const matches = prepaid || Number(amount) === cod
  return (
    <Sheet
      title="Xác nhận giao thành công"
      description={
        prepaid
          ? 'Đơn đã thanh toán trước, không thu tiền người nhận.'
          : `Thu hộ COD ${formatMoney(cod)}. Nhập đúng số tiền đã thu.`
      }
      onClose={onClose}
    >
      {!prepaid && (
        <Field
          label="Số tiền đã thu (VND)"
          value={amount}
          keyboardType="number-pad"
          onChangeText={(value) => setAmount(value.replace(/\D/g, ''))}
          error={amount && !matches ? 'Số tiền phải khớp số COD của đơn' : ''}
        />
      )}
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <Button
        title="Xác nhận đã giao"
        loading={busy}
        disabled={!matches}
        onPress={() => onSubmit({ codCollected: prepaid ? 0 : Number(amount) })}
      />
      <Button title="Huỷ" variant="secondary" onPress={onClose} />
    </Sheet>
  )
}

export function FailSheet({ assignment, busy, error, onClose, onSubmit }) {
  const [code, setCode] = useState('')
  const [note, setNote] = useState('')
  const selected = FAILURE_REASONS.find((reason) => reason.code === code)
  const last = assignment.attempt >= (assignment.maxAttempts || MAX_ATTEMPTS)
  return (
    <Sheet title="Báo giao thất bại" onClose={onClose}>
      <View accessibilityRole="radiogroup" style={styles.reasons}>
        {FAILURE_REASONS.map((reason) => (
          <Pressable
            key={reason.code}
            accessibilityRole="radio"
            accessibilityState={{ checked: reason.code === code, disabled: busy }}
            disabled={busy}
            onPress={() => setCode(reason.code)}
            style={[styles.reason, reason.code === code && styles.selected]}
          >
            <Text style={styles.reasonLabel}>{reason.label}</Text>
          </Pressable>
        ))}
      </View>
      {(last || selected?.returnImmediately) && (
        <Notice tone="warning">Chuyến này sẽ chuyển ngay sang hoàn hàng về gian hàng.</Notice>
      )}
      <Field
        label={selected?.noteRequired ? 'Ghi chú nội bộ (bắt buộc)' : 'Ghi chú nội bộ'}
        value={note}
        onChangeText={setNote}
        maxLength={255}
        multiline
      />
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <Button
        title="Xác nhận thất bại"
        variant="danger"
        loading={busy}
        disabled={!selected || (selected.noteRequired && !note.trim())}
        onPress={() => onSubmit({ reasonCode: code, ...(note.trim() && { reason: note.trim() }) })}
      />
      <Button title="Huỷ" variant="secondary" onPress={onClose} />
    </Sheet>
  )
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(17,24,39,0.45)' },
  sheet: {
    backgroundColor: colors.surface,
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    padding: spacing(5),
    paddingBottom: spacing(8),
    maxHeight: '90%',
  },
  content: { gap: spacing(3) },
  title: { fontSize: 18, fontWeight: '700', color: colors.text },
  description: { fontSize: 14, color: colors.muted },
  reasons: { gap: spacing(2) },
  reason: {
    padding: spacing(3),
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
  },
  selected: { borderColor: colors.primary, backgroundColor: colors.primarySoft },
  reasonLabel: { color: colors.text, fontSize: 14 },
})
