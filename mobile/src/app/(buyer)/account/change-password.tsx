import { useRouter } from 'expo-router'
import { useState } from 'react'

import { changePassword } from '@/api/user'
import { getApiError, type ApiError } from '@/api/client'
import { useAuth } from '@/auth/AuthProvider'
import { AuthStore } from '@/auth/session'
import { AppText, Button, InfoCard, Screen, TextField, useToast } from '@/components/ui'
import { useQueryClient } from '@tanstack/react-query'
import { accountRoutes } from '@/features/account/routes'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'
import {
  PASSWORD_HINT,
  validatePasswordChange,
  type PasswordValues,
} from '@/features/account/validation'

const EMPTY: PasswordValues = { currentPassword: '', newPassword: '', confirmPassword: '' }

export default function ChangePasswordScreen() {
  const router = useRouter()
  const toast = useToast()
  const client = useQueryClient()
  const { user } = useAuth()
  // Set before the deliberate sign-out so the guest guard does not push login?returnTo=<this screen>.
  const [ended, setEnded] = useState(false)
  useGuestRedirect(!ended)
  const [values, setValues] = useState<PasswordValues>(EMPTY)
  const [errors, setErrors] = useState<Partial<Record<keyof PasswordValues, string>>>({})
  const [failure, setFailure] = useState<ApiError | null>(null)
  const [saving, setSaving] = useState(false)
  // After 504 PASSWORD_CHANGE_UNKNOWN the request must never be resent.
  const unknown = failure?.code === 'PASSWORD_CHANGE_UNKNOWN'

  function setField(name: keyof PasswordValues, value: string) {
    setValues((v) => ({ ...v, [name]: value }))
    setErrors((e) => ({ ...e, [name]: undefined }))
    if (!unknown) setFailure(null)
  }

  function endSession(message: string) {
    const userId = user?.userId
    setEnded(true)
    AuthStore.clear()
    client.removeQueries({ queryKey: ['user', userId] })
    toast.show({ type: 'info', message })
    router.replace(accountRoutes.login)
  }

  async function submit() {
    if (saving || unknown) return
    const found = validatePasswordChange(values)
    setErrors(found)
    setFailure(null)
    if (Object.keys(found).length) return
    setSaving(true)
    try {
      await changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      })
    } catch (e) {
      const err = getApiError(e)
      const fields: Partial<Record<keyof PasswordValues, string>> = {}
      if (err.code === 'INVALID_CURRENT_PASSWORD') fields.currentPassword = err.message
      else if (err.code === 'PASSWORD_UNCHANGED') fields.newPassword = err.message
      else if (err.fieldErrors.newPassword) fields.newPassword = err.fieldErrors.newPassword
      else if (err.fieldErrors.currentPassword)
        fields.currentPassword = err.fieldErrors.currentPassword
      setErrors(fields)
      setFailure(err)
      setSaving(false)
      return
    }
    // The server revoked every refresh token (this device too): drop the session and log in again.
    endSession('Đã đổi mật khẩu. Vui lòng đăng nhập lại bằng mật khẩu mới.')
  }

  const hasFieldError = Object.values(errors).some(Boolean)
  const locked = saving || unknown

  return (
    <Screen title="Đổi mật khẩu" onBack={() => router.back()} testID="change-password">
      <AppText variant="body">Xác nhận bằng mật khẩu hiện tại</AppText>
      <TextField
        testID="password-current"
        label="Mật khẩu hiện tại"
        required
        secure
        autoComplete="current-password"
        value={values.currentPassword}
        onChangeText={(t) => setField('currentPassword', t)}
        error={errors.currentPassword}
        disabled={locked}
      />
      <TextField
        testID="password-new"
        label="Mật khẩu mới"
        required
        secure
        autoComplete="new-password"
        helper={PASSWORD_HINT}
        value={values.newPassword}
        onChangeText={(t) => setField('newPassword', t)}
        error={errors.newPassword}
        disabled={locked}
      />
      <TextField
        testID="password-confirm"
        label="Xác nhận mật khẩu mới"
        required
        secure
        autoComplete="new-password"
        value={values.confirmPassword}
        onChangeText={(t) => setField('confirmPassword', t)}
        error={errors.confirmPassword}
        disabled={locked}
      />
      {unknown ? (
        <>
          <InfoCard
            testID="password-unknown"
            title="Chưa rõ mật khẩu đã được đổi hay chưa"
            body="Vui lòng không gửi lại yêu cầu. Hãy đăng nhập lại bằng mật khẩu mới; nếu không được thì dùng mật khẩu cũ."
          />
          <Button
            testID="password-relogin"
            title="Đăng nhập lại"
            onPress={() => endSession('Vui lòng đăng nhập lại.')}
          />
        </>
      ) : failure && !hasFieldError ? (
        <InfoCard testID="password-failure" title="Chưa đổi được mật khẩu" body={failure.message}>
          {failure.code === 'PASSWORD_NOT_SET' ? (
            <Button
              title="Đặt mật khẩu qua Quên mật khẩu"
              kind="secondary"
              size="M"
              onPress={() => router.push(accountRoutes.forgotPassword)}
            />
          ) : null}
        </InfoCard>
      ) : null}
      <Button
        testID="password-submit"
        title="Đổi mật khẩu"
        onPress={submit}
        loading={saving}
        disabled={unknown}
      />
      <Button
        testID="password-back"
        title="Quay lại"
        kind="secondary"
        onPress={() => router.back()}
        disabled={saving}
      />
    </Screen>
  )
}
