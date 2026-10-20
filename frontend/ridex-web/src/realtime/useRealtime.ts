import { useContext, useEffect, useRef } from 'react'
import { RealtimeContext, type RealtimeContextValue } from './RealtimeContext'

export function useRealtime(): RealtimeContextValue {
  const value = useContext(RealtimeContext)
  if (!value) {
    throw new Error('useRealtime must be used inside <RealtimeProvider>')
  }
  return value
}

/**
 * Listen to one destination while the component is mounted (and `enabled`). The latest `handler` is always
 * the one called, without re-subscribing every render. The subscription is removed on unmount, so leaving a
 * screen never leaves a listener running.
 */
export function useSubscription<T = unknown>(destination: string, handler: (body: T) => void, enabled = true): void {
  const { client } = useRealtime()
  const latest = useRef(handler)

  useEffect(() => {
    latest.current = handler
  })

  useEffect(() => {
    if (!enabled) return
    return client.subscribe(destination, (body) => latest.current(body as T))
  }, [client, destination, enabled])
}
