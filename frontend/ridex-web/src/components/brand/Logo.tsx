import { cn } from '@/lib/cn'

/** RideX mark: a gold rounded square holding a stylised route pin, plus the wordmark. */
export function Logo({ className, showWordmark = true }: { className?: string; showWordmark?: boolean }) {
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <span
        aria-hidden
        className="grid size-9 place-items-center rounded-md border border-accent/40 bg-accent-subtle shadow-neu"
      >
        <svg viewBox="0 0 24 24" className="size-5 text-accent" fill="none" stroke="currentColor" strokeWidth="2.2">
          <path d="M5 19c0-5 5-3 7-7s-1-6 3-7" strokeLinecap="round" />
          <circle cx="5" cy="19" r="2" fill="currentColor" stroke="none" />
          <circle cx="16" cy="5" r="2.4" fill="currentColor" stroke="none" />
        </svg>
      </span>
      {showWordmark ? <span className="text-xl font-bold tracking-tight text-fg">RideX</span> : null}
      {!showWordmark ? <span className="sr-only">RideX</span> : null}
    </span>
  )
}
