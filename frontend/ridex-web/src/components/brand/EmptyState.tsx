import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'

/** Honest "nothing here yet" panel. Used both for empty data and for features still to come. */
export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center gap-3 panel rounded-xl border border-dashed border-border-strong bg-surface/50 px-6 py-12 text-center">
      <span className="grid size-12 place-items-center rounded-md bg-gradient-to-br from-accent/25 to-accent/5 text-accent ring-1 ring-accent/25">
        <Icon className="size-6" aria-hidden />
      </span>
      <h2 className="text-lg font-semibold text-fg">{title}</h2>
      <p className="max-w-md text-sm text-fg-secondary">{description}</p>
      {action}
    </div>
  )
}
