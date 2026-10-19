import { Check } from 'lucide-react'
import type { RideStatus } from '@/api/types'
import { cn } from '@/lib/cn'
import { PROGRESS_STEPS, progressIndex } from '@/lib/rides'

/** The steps of a ride. The current step is marked for screen readers, not just by colour. */
export function RideProgress({ status }: { status: RideStatus }) {
  const current = progressIndex(status)
  return (
    <ol className="flex flex-wrap gap-x-2 gap-y-3" aria-label="Ride progress">
      {PROGRESS_STEPS.map((step, index) => {
        const done = current > index || status === 'RIDE_COMPLETED'
        const active = current === index && status !== 'RIDE_COMPLETED'
        return (
          <li
            key={step.key}
            aria-current={active ? 'step' : undefined}
            className="flex items-center gap-2 text-sm"
          >
            <span
              className={cn(
                'grid size-7 shrink-0 place-items-center rounded-full border text-xs font-bold transition-colors duration-(--dur-slow)',
                done && 'border-success bg-success/15 text-success',
                active && 'border-accent bg-accent-subtle text-accent',
                !done && !active && 'border-border-strong text-fg-muted',
              )}
            >
              {done ? <Check className="size-4" aria-hidden /> : index + 1}
            </span>
            <span className={cn(active ? 'font-semibold text-fg' : done ? 'text-fg-secondary' : 'text-fg-muted')}>
              {step.label}
            </span>
            {index < PROGRESS_STEPS.length - 1 && <span aria-hidden className="mx-1 hidden h-px w-6 bg-border-strong sm:block" />}
          </li>
        )
      })}
    </ol>
  )
}
