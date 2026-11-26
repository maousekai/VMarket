import { useState } from 'react'
import { KeyboardAvoidingView, Modal, Platform, StyleSheet, Text, View } from 'react-native'
import { Button, Field, Notice } from '../components/ui'
import { formatMoney } from '../api/deliveryApi'
import { colors, radius, spacing } from '../theme'

function Sheet({ title, description, onClose, children }) {
  return (
    <Modal transparent animationType="slide" visible onRequestClose={onClose}>
      <KeyboardAvoidingView
        style={styles.backdrop}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View style={styles.sheet} accessibilityViewIsModal>
          <Text style={styles.title} accessibilityRole="header">
            {title}
          </Text>
          {description ? <Text style={styles.description}>{description}</Text> : null}
          {children}
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
  const [reason, setReason] = useState('')
  const last = assignment.attempt >= 3
  return (
    <Sheet
      title="Báo giao thất bại"
      description={
        last
          ? 'Đây là lần giao thứ 3: đơn sẽ chuyển sang hoàn hàng về gian hàng.'
          : `Lần giao ${assignment.attempt || 1}/3. Bạn có thể giao lại sau.`
      }
      onClose={onClose}
    >
      <Field
        label="Lý do"
        value={reason}
        onChangeText={setReason}
        maxLength={255}
        multiline
        placeholder="Ví dụ: không liên lạc được người nhận"
      />
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <Button
        title="Xác nhận thất bại"
        variant="danger"
        loading={busy}
        disabled={!reason.trim()}
        onPress={() => onSubmit({ reason: reason.trim() })}
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
    gap: spacing(3),
  },
  title: { fontSize: 18, fontWeight: '700', color: colors.text },
  description: { fontSize: 14, color: colors.muted },
})
