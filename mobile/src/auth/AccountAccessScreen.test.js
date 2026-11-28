import { fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import {
  registerBuyer,
  requestPasswordReset,
  requestRegistrationOtp,
  resetPassword,
  verifyRegistrationOtp,
} from '../api/authApi'
import AccountAccessScreen from './AccountAccessScreen'

jest.mock('expo-router', () => ({ Link: () => null }))
jest.mock('../api/authApi', () => ({
  registerBuyer: jest.fn(),
  requestPasswordReset: jest.fn(),
  requestRegistrationOtp: jest.fn(),
  resetPassword: jest.fn(),
  verifyRegistrationOtp: jest.fn(),
}))

const email = 'buyer@example.com'
const password = 'Buyer123!'
const otp = '123456'
const change = (label, value) => fireEvent.changeText(screen.getByLabelText(label), value)
const press = (name) => fireEvent.press(screen.getByRole('button', { name }))

it('registers a buyer and verifies the emailed OTP', async () => {
  await render(<AccountAccessScreen register />)
  await change('Email', email)
  await change('Tên đăng nhập', 'buyer')
  await change('Mật khẩu', password)
  await press('Tạo tài khoản')

  expect(await screen.findByLabelText('Mã OTP')).toBeTruthy()
  expect(registerBuyer).toHaveBeenCalledWith({ email, username: 'buyer', password })
  expect(requestRegistrationOtp).toHaveBeenCalledWith(email)
  await change('Mã OTP', otp)
  await press('Xác thực email')
  await waitFor(() => expect(verifyRegistrationOtp).toHaveBeenCalledWith({ email, otp }))
})

it('requests a password reset code and submits the new password', async () => {
  await render(<AccountAccessScreen />)
  await change('Email', email)
  await press('Gửi mã đặt lại')
  expect(await screen.findByLabelText('Mã OTP')).toBeTruthy()
  expect(requestPasswordReset).toHaveBeenCalledWith(email)
  await change('Mã OTP', otp)
  await change('Mật khẩu mới', password)
  await press('Đặt lại mật khẩu')

  expect(await screen.findByText(/Đã đổi mật khẩu/)).toBeTruthy()
  expect(resetPassword).toHaveBeenCalledWith({ email, code: otp, newPassword: password })
})
