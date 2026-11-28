import { useState } from 'react'
import { router } from 'expo-router'
import {
  registerBuyer,
  requestPasswordReset,
  requestRegistrationOtp,
  resetPassword,
  verifyRegistrationOtp,
} from '../api/authApi'
import { getApiError } from '../api/client'
import { Body, Button, Field, Heading, Notice, Screen } from '../components/ui'

export default function AccountAccessScreen({ register = false }) {
  const [step, setStep] = useState('email')
  const [email, setEmail] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [resent, setResent] = useState(false)
  const address = email.trim()
  const verifying = step === 'code'
  // Tieu de theo frame Figma: 3.65 / 3.66 (dang ky), 3.68 / 3.70 (quen mat khau).
  const title = register
    ? verifying
      ? 'Xác thực email'
      : 'Đăng ký VMarket'
    : verifying || step === 'done'
      ? 'Đặt lại mật khẩu'
      : 'Quên mật khẩu'
  const requestCode = register ? requestRegistrationOtp : requestPasswordReset

  async function run(action) {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(getApiError(failure))
    } finally {
      setBusy(false)
    }
  }

  const start = () =>
    run(async () => {
      if (register) {
        await registerBuyer({ email: address, username: username.trim(), password })
        setPassword('')
        setStep('code')
        await requestCode(address)
      } else {
        await requestCode(address)
        setStep('code')
      }
    })

  const confirm = () =>
    run(async () => {
      if (register) await verifyRegistrationOtp({ email: address, otp: code })
      else {
        await resetPassword({ email: address, code, newPassword: password })
        setPassword('')
        setStep('done')
      }
    })

  return (
    <Screen title={title}>
      <Heading>{title}</Heading>
      {step === 'done' ? (
        <Notice tone="success">Đã đổi mật khẩu. Hãy đăng nhập bằng mật khẩu mới.</Notice>
      ) : (
        <>
          {verifying ? (
            <Body muted>{`Nhập mã xác thực gửi đến ${address}.`}</Body>
          ) : (
            <>
              {!register && (
                <Body muted>Nhập email đã đăng ký, VMarket sẽ gửi mã để đặt lại mật khẩu.</Body>
              )}
              <Field
                label="Email"
                value={email}
                onChangeText={setEmail}
                autoCapitalize="none"
                autoComplete="email"
                keyboardType="email-address"
                error={error?.fieldErrors.email}
              />
            </>
          )}
          {verifying && (
            <Field
              label="Mã OTP"
              value={code}
              onChangeText={(value) => setCode(value.replace(/\D/g, ''))}
              keyboardType="number-pad"
              autoComplete="one-time-code"
              maxLength={6}
              error={error?.fieldErrors.otp || error?.fieldErrors.code}
            />
          )}
          {register && !verifying && (
            <Field
              label="Tên đăng nhập"
              value={username}
              onChangeText={setUsername}
              autoCapitalize="none"
              autoComplete="username"
              error={error?.fieldErrors.username}
            />
          )}
          {register !== verifying && (
            <Field
              label={register ? 'Mật khẩu' : 'Mật khẩu mới'}
              value={password}
              onChangeText={setPassword}
              secure
              autoComplete="new-password"
              hint="8–32 ký tự, có chữ hoa, số và ký tự đặc biệt."
              error={error?.fieldErrors.password || error?.fieldErrors.newPassword}
            />
          )}
          {error && <Notice tone="danger">{error.message}</Notice>}
          {resent && !error && <Notice tone="success">Đã gửi lại mã xác thực.</Notice>}
          {verifying ? (
            <>
              <Button
                title={register ? 'Xác thực email' : 'Đặt lại mật khẩu'}
                onPress={confirm}
                loading={busy}
                disabled={code.length !== 6 || (!register && !password)}
              />
              <Button
                title="Gửi lại mã"
                variant="secondary"
                disabled={busy}
                onPress={() =>
                  run(async () => {
                    setResent(false)
                    await requestCode(address)
                    setResent(true)
                  })
                }
              />
            </>
          ) : (
            <Button
              title={register ? 'Tạo tài khoản' : 'Gửi mã đặt lại'}
              onPress={start}
              loading={busy}
              disabled={!address || (register && (!username.trim() || !password))}
            />
          )}
        </>
      )}
      <Button
        title="Về đăng nhập"
        variant="secondary"
        onPress={() => (router.canGoBack() ? router.back() : router.replace('/login'))}
      />
    </Screen>
  )
}
