import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { refreshSession } from '@/api/session'
import { tokenStorage } from '@/auth/tokenStorage'
import { RealtimeClient, tokenExpiryMs, type TokenInfo } from './RealtimeClient'
import { RealtimeContext } from './RealtimeContext'

/** A usable access token, refreshed first when it is missing or about to expire. */
async function getToken(): Promise<TokenInfo | null> {
  let token = tokenStorage.getAccessToken()
  let expiresAtMs = token ? tokenExpiryMs(token) : null
  if (!token || !expiresAtMs || expiresAtMs - Date.now() < 30_000) {
    try {
      token = (await refreshSession()).accessToken
      expiresAtMs = tokenExpiryMs(token)
    } catch {
      return null
    }
  }
  return token && expiresAtMs ? { token, expiresAtMs } : null
}

function socketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  // Same address as the page: the dev server proxies /ws to the backend, a gateway does it in production
  return `${scheme}://${window.location.host}/ws`
}

/**
 * One WebSocket for the whole signed-in area. It connects when this component mounts (you are logged in)
 * and closes when it unmounts (you logged out).
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [client] = useState(() => new RealtimeClient({ url: socketUrl(), getToken }))
  const [state, setState] = useState(client.state)

  useEffect(() => {
    const stopListening = client.onState(setState)
    client.start()
    return () => {
      stopListening()
      client.stop()
    }
  }, [client])

  const value = useMemo(() => ({ client, state }), [client, state])
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>
}
