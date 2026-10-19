import { forwardRef, useId, type ReactNode, type SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'
import { Label } from './label'

/** A native select, styled like our inputs (native = best accessibility and mobile behaviour). */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  ({ className, children, ...props }, ref) => (
    <select
      ref={ref}
      className={cn(
        'h-11 w-full rounded-md border border-border-strong bg-canvas px-3 text-base text-fg shadow-neu-inset',
        'transition-colors duration-(--dur-base) ease-(--ease-standard) hover:border-fg-muted',
        'focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40',
        className,
      )}
      {...props}
    >
      {children}
    </select>
  ),
)
Select.displayName = 'Select'

export function SelectField({
  label,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string; children: ReactNode }) {
  const id = useId()
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Select id={id} {...props}>
        {children}
      </Select>
    </div>
  )
}
