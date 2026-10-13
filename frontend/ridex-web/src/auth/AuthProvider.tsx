import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import axios from 'axios'
import { api } from '../api/client'
import { endSession, onSessionChange, refreshSession, startSession } from '../api/session'
import type { LoginResponse, UserSummary } from '../api/types'
import { AuthContext, type AuthContextValue, type AuthStatus } from './AuthContext'
import { tokenStorage } from './tokenStorage'

interface AuthState {
  status: AuthStatus
  user: UserSummary | null
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // With no stored refresh token there is nothing to restore, so we start anonymous
  const [state, setState] = useState<AuthState>(() => ({
    status: tokenStorage.getRefreshToken() ? 'loading' : 'anonymous',
    user: null,
  }))

  useEffect(() => {
    // Single source of truth: login, refresh and logout all announce themselves here
    const unsubscribe = onSessionChange((user) =>
      setState(user ? { status: 'authenticated', user } : { status: 'anonymous', user: null }),
    )

    // Page (re)load: the access token only lived in memory, so get a new one
    if (tokenStorage.getRefreshToken()) {
      refreshSession().catch(() => setState({ status: 'anonymous', user: null }))
    }
    return unsubscribe
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const { data } = await api.post<LoginResponse>('/api/auth/login', { email, password })
    startSession(data)
    return data.user
  }, [])

  const logout = useCallback(async () => {
    const refreshToken = tokenStorage.getRefreshToken()
    try {
      // Tell the server so the refresh token dies there too. Failure must not trap the user.
      if (refreshToken) {
        await axios.post('/api/auth/logout', { refreshToken })
      }
    } catch {
      // ignore: we still end the local session below
    }
    endSession()
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ status: state.status, user: state.user, login, logout }),
    [state, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
