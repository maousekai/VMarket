import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { onSessionChange, refreshAccessToken, restoreTokens } from '../api/client'
import * as authApi from '../api/authApi'
import { sessionFromToken } from './session'

const AuthContext = createContext(null)

/**
 * Phien dang nhap cua app. Khoi dong: doc token tu SecureStore; access token het han thi refresh
 * ngay (refresh token con han -> vao thang app, khong phai dang nhap lai).
 */
export function AuthProvider({ children }) {
  const [state, setState] = useState({ status: 'loading', session: null })

  useEffect(() => {
    let active = true
    const apply = (token) => {
      if (!active) return
      const session = token ? sessionFromToken(token) : null
      setState({ status: session ? 'signedIn' : 'signedOut', session })
    }
    const unsubscribe = onSessionChange(apply)
    ;(async () => {
      try {
        const { accessToken, refreshToken } = await restoreTokens()
        const session = accessToken ? sessionFromToken(accessToken) : null
        const fresh = session && (!session.expiresAt || session.expiresAt - Date.now() > 30_000)
        if (fresh) return apply(accessToken)
        if (refreshToken) return apply(await refreshAccessToken())
        apply(null)
      } catch (error) {
        // Mat mang luc mo app: van dung phien cu neu co, request sau se tu refresh.
        const { accessToken } = await restoreTokens().catch(() => ({}))
        apply(error?.response ? null : accessToken)
      }
    })()
    return () => {
      active = false
      unsubscribe()
    }
  }, [])

  const login = useCallback((values) => authApi.login(values), [])
  const logout = useCallback(() => authApi.logout(), [])

  const value = useMemo(() => ({ ...state, login, logout }), [state, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}

export { AuthContext }
