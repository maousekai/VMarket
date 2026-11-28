import client, { signOutLocally, storeAuthResponse } from './client'
import { loadTokens } from '../auth/tokenStore'

export async function login({ email, password }) {
  const response = await client.post('/api/auth/login', { email: email.trim(), password })
  await storeAuthResponse(response)
  return response.data
}

const authPost = async (path, body) => (await client.post(`/api/auth/${path}`, body)).data

export const registerBuyer = ({ email, username, password }) =>
  authPost('register', { email, username, password })

export const requestRegistrationOtp = (email) => authPost('otp/request', { email })

export async function verifyRegistrationOtp({ email, otp }) {
  const response = await client.post('/api/auth/otp/verify', { email, otp })
  await storeAuthResponse(response)
}

export const requestPasswordReset = (email) => authPost('password/forgot', { email })

export const resetPassword = ({ email, code, newPassword }) =>
  authPost('password/reset', { email, code, newPassword })

/** Thu hoi refresh token o server (neu con mang) roi luon xoa token tren may. */
export async function logout() {
  try {
    const { refreshToken } = await loadTokens()
    if (refreshToken)
      await client.post('/api/auth/logout', null, {
        headers: { Cookie: `refresh_token=${encodeURIComponent(refreshToken)}` },
      })
  } catch {
    // Mat mang hoac token da het han: van dang xuat tren thiet bi.
  } finally {
    await signOutLocally()
  }
}
