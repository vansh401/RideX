import { forwardRef, type InputHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type = 'text', ...props }, ref) => (
    <input
      ref={ref}
      type={type}
      className={cn(
        'h-11 w-full rounded-md border border-border-strong bg-canvas px-3.5 text-base text-fg',
        'placeholder:text-fg-muted shadow-neu-inset',
        'transition-colors duration-(--dur-base) ease-(--ease-standard)',
        'hover:border-fg-muted focus-visible:border-accent focus-visible:outline-none',
        'focus-visible:ring-2 focus-visible:ring-accent/40',
        'aria-[invalid=true]:border-danger aria-[invalid=true]:ring-danger/30',
        'disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  ),
)
Input.displayName = 'Input'
