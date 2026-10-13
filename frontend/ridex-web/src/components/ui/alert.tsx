import { AlertCircle, CheckCircle2, Info } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

const styles = {
  error: { box: 'border-danger/30 bg-danger/10 text-danger', Icon: AlertCircle },
  success: { box: 'border-success/30 bg-success/10 text-success', Icon: CheckCircle2 },
  info: { box: 'border-info/30 bg-info/10 text-info', Icon: Info },
} as const

/** Inline message. Errors are announced immediately (role=alert), the rest politely (status). */
export function Alert({
  kind,
  children,
  className,
}: {
  kind: keyof typeof styles
  children: ReactNode
  className?: string
}) {
  const { box, Icon } = styles[kind]
  return (
    <div
      role={kind === 'error' ? 'alert' : 'status'}
      className={cn('flex items-start gap-3 rounded-md border px-4 py-3 text-sm', box, className)}
    >
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}
