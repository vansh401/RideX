import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** Loading placeholder. Pulses via CSS, which the reduced-motion rule neutralises. */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn('animate-pulse rounded-md bg-elevated', className)} {...props} />
}
