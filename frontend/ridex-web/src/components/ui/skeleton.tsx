import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** Loading placeholder with a soft shimmer (the reduced-motion rule neutralises the animation). */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn('skeleton rounded-md', className)} {...props} />
}
