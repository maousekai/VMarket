import { useState } from 'react'
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  Text,
  View,
} from 'react-native'
import { formatMoney, shortOrderId } from '../api/deliveryApi'
import { Body, Button, Card, Field, Heading, Label, Notice, Price } from '../components/ui'
import { FAILURE_REASONS, MAX_ATTEMPTS } from '../deliveryStatus'
import { makeStyles, radius, spacing, type } from '../theme'

function Sheet({ onClose, children }) {
  const styles = useStyles()
  return (
    <Modal transparent animationType="slide" visible onRequestClose={onClose}>
      <KeyboardAvoidingView
        style={styles.backdrop}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View style={styles.sheet} accessibilityViewIsModal>
          {/* handled: bam nut khi ban phim dang mo thi nut nhan luon, khong chi dong ban phim. */}
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.content}>
            {children}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  )
}

/**
 * Xac nhan COD (frame 3.15): so tien can thu, luu y truoc khi xac nhan, "Da thu du" gui dung so tien
 * COD (backend tu choi neu lech - COD_AMOUNT_MISMATCH). Don tra truoc chi xac nhan da giao.
 */
export function DeliverSheet({ assignment, busy, error, onClose, onSubmit }) {
  const cod = Number(assignment.codAmount || 0)
  const prepaid = cod === 0
  return (
    <Sheet onClose={onClose}>
      <Heading>{prepaid ? 'Xác nhận giao thành công' : 'Xác nhận COD'}</Heading>
      <Label>
        {shortOrderId(assignment.orderId)} ·{' '}
        {prepaid ? 'Đã thanh toán trước' : `COD ${formatMoney(cod)}`}
      </Label>
      {prepaid ? (
        <Body>Đơn đã thanh toán trước, không thu tiền người nhận.</Body>
      ) : (
        <>
          <Price large>{formatMoney(cod)}</Price>
          <Card title="Trước khi xác nhận">
            <Body>
              {`Chỉ bấm khi người nhận đã nhận hàng và bạn đã thu đủ ${formatMoney(cod)} tiền mặt.`}
            </Body>
          </Card>
        </>
      )}
      {error ? <Notice tone="danger">{`Chưa lưu được: ${error}`}</Notice> : null}
      <Button
        title={prepaid ? 'Xác nhận đã giao' : `Đã thu đủ ${formatMoney(cod)}`}
        loading={busy}
        onPress={() => onSubmit({ codCollected: prepaid ? 0 : cod })}
      />
      <Button
        title={prepaid ? 'Huỷ' : 'Chưa thu đủ tiền'}
        variant="secondary"
        disabled={busy}
        onPress={onClose}
      />
    </Sheet>
  )
}

/** Giao that bai (frame 3.16): 6 ly do chuan, "Khac" bat buoc ghi chu; ghi chu khong hien cho nguoi mua. */
export function FailSheet({ assignment, busy, error, onClose, onSubmit }) {
  const styles = useStyles()
  const [code, setCode] = useState('')
  const [note, setNote] = useState('')
  const selected = FAILURE_REASONS.find((reason) => reason.code === code)
  const last = assignment.attempt >= (assignment.maxAttempts || MAX_ATTEMPTS)
  return (
    <Sheet onClose={onClose}>
      <Heading>Giao thất bại</Heading>
      <Label>{`${shortOrderId(assignment.orderId)} · Lần giao ${assignment.attempt || 1}/${assignment.maxAttempts || MAX_ATTEMPTS}`}</Label>
      <Label>Lý do giao thất bại *</Label>
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
            <Text style={[styles.reasonLabel, reason.code === code && styles.reasonSelected]}>
              {reason.label}
            </Text>
          </Pressable>
        ))}
      </View>
      {(last || selected?.returnImmediately) && (
        <Notice tone="warning">Chuyến này sẽ chuyển ngay sang hoàn hàng về gian hàng.</Notice>
      )}
      <Field
        label={selected?.noteRequired ? 'Ghi chú (bắt buộc)' : 'Ghi chú'}
        hint="Ghi chú nội bộ, người mua không thấy."
        value={note}
        onChangeText={setNote}
        maxLength={255}
        multiline
      />
      {error ? <Notice tone="danger">{`Chưa lưu được giao thất bại: ${error}`}</Notice> : null}
      <Button
        title="Lưu lý do giao thất bại"
        loading={busy}
        disabled={!selected || (selected.noteRequired && !note.trim())}
        onPress={() => onSubmit({ reasonCode: code, ...(note.trim() && { reason: note.trim() }) })}
      />
      <Button title="Huỷ" variant="secondary" disabled={busy} onPress={onClose} />
    </Sheet>
  )
}

const useStyles = makeStyles((colors) => ({
  backdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.5)' },
  sheet: {
    backgroundColor: colors.background,
    borderTopLeftRadius: radius.lg,
    borderTopRightRadius: radius.lg,
    maxHeight: '92%',
  },
  content: { gap: spacing(3), padding: spacing(5), paddingBottom: spacing(8) },
  reasons: { gap: spacing(2) },
  reason: {
    minHeight: 48,
    justifyContent: 'center',
    paddingHorizontal: spacing(4),
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  selected: { borderColor: colors.primary, backgroundColor: colors.primarySoft },
  reasonLabel: { ...type.body, color: colors.muted },
  reasonSelected: { color: colors.text, fontWeight: '600' },
}))
