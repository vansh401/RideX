import { createContext } from 'react'
import type { UserSummary } from '../api/types'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous'

export interface AuthContextValue {
  status: AuthStatus
  user: UserSummary | null
  login: (email: string, password: string) => Promise<UserSummary>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
