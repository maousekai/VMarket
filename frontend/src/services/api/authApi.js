import apiClient from './apiClient'

const post = async (path, body) => (await apiClient.post(`/api/auth${path}`, body)).data

export const registerAccount = ({ email, username, password }) =>
  post('/register', { email, username, password })
export const loginAccount = ({ identifier, password }) => post('/login', { identifier, password })
export const requestOtp = ({ email }) => post('/otp/request', { email })
export const verifyOtp = ({ email, otp }) => post('/otp/verify', { email, otp })
export const refreshSession = () => post('/refresh')
export const logoutAccount = () => post('/logout')
export const forgotPassword = ({ email }) => post('/password/forgot', { email })
export const resetPassword = ({ email, code, newPassword }) =>
  post('/password/reset', { email, code, newPassword })
