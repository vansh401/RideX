import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { useSpotlight } from '@/lib/useSpotlight'

/** A glass window. Pass `interactive` for clickable cards: they lift, tilt a little and catch the pointer's light. */
export function Card({
  className,
  interactive = false,
  ...props
}: HTMLAttributes<HTMLDivElement> & { interactive?: boolean }) {
  const spotlight = useSpotlight()
  return (
    <div
      className={cn('panel rounded-lg border border-border', interactive && 'lift', className)}
      {...(interactive ? spotlight : {})}
      {...props}
    />
  )
}

export function CardHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1 p-6 pb-0', className)} {...props} />
}

export function CardTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-xl font-semibold leading-7 text-fg', className)} {...props} />
}

export function CardDescription({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-sm text-fg-secondary', className)} {...props} />
}

export function CardContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('p-6', className)} {...props} />
}
