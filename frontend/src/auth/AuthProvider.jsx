import { useEffect, useSyncExternalStore } from 'react'
import { AuthContext } from './useAuth'
import { sessionManager } from './session'

export function AuthProvider({ children, manager = sessionManager }) {
  const snapshot = useSyncExternalStore(manager.subscribe, manager.getSnapshot, manager.getSnapshot)

  useEffect(() => manager.start(), [manager])

  const userId = snapshot.session?.userId
  const status = snapshot.session?.status
  useEffect(() => {
    if (userId && status === 'ACTIVE') manager.loadProfile().catch(() => {})
  }, [manager, userId, status])

  return <AuthContext.Provider value={{ ...manager, ...snapshot }}>{children}</AuthContext.Provider>
}
