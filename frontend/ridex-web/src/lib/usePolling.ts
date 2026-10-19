import { useCallback, useEffect, useRef, useState } from 'react'
import { toFriendlyError } from '@/api/errors'

interface PollingState<T> {
  data: T | null
  error: string | null
  loading: boolean
  /** Fetch again right now (e.g. after the user pressed a button). */
  refresh: () => Promise<void>
}

/**
 * Loads data and keeps it fresh: fetches once, then every `intervalMs` while `enabled`.
 *
 * Why polling: live updates through WebSockets arrive in Phase 5. Until then a short poll is the
 * honest way to follow a ride. It pauses while the browser tab is hidden (no wasted requests) and
 * never lets two requests overlap, so a slow server is not hammered.
 */
export function usePolling<T>(
  fetcher: () => Promise<T>,
  { intervalMs, enabled = true }: { intervalMs: number; enabled?: boolean },
): PollingState<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const inFlight = useRef(false)
  const latest = useRef(fetcher)

  useEffect(() => {
    latest.current = fetcher
  })

  const run = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      const result = await latest.current()
      setData(result)
      setError(null)
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      inFlight.current = false
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!enabled) return
    void run()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void run()
    }, intervalMs)
    return () => window.clearInterval(timer)
  }, [enabled, intervalMs, run])

  return { data, error, loading, refresh: run }
}
