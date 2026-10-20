import { createContext } from 'react'
import type { ConnectionState, RealtimeClient } from './RealtimeClient'

export interface RealtimeContextValue {
  client: RealtimeClient
  state: ConnectionState
}

export const RealtimeContext = createContext<RealtimeContextValue | null>(null)
