import { cn } from '@/lib/cn'
import { useRealtime } from './useRealtime'

const LABEL = {
  live: 'Live',
  connecting: 'Connecting',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
} as const

/** A small dot that tells the person whether live updates are working right now. */
export function ConnectionBadge() {
  const { state } = useRealtime()
  return (
    <span
      className="hidden items-center gap-1.5 text-xs text-fg-muted sm:inline-flex"
      role="status"
      aria-label={`Live updates: ${LABEL[state]}`}
      title={state === 'live' ? 'Live updates are on' : 'Live updates are not connected. The page still refreshes by itself.'}
    >
      <span
        aria-hidden
        className={cn('size-2 rounded-full', state === 'live' ? 'bg-success' : 'animate-pulse bg-warning')}
      />
      {LABEL[state]}
    </span>
  )
}
