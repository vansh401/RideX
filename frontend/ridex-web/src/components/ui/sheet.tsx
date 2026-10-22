import { Dialog as SheetPrimitive } from 'radix-ui'
import { X } from 'lucide-react'
import type { ComponentProps } from 'react'
import { cn } from '@/lib/cn'

export const Sheet = SheetPrimitive.Root
export const SheetTrigger = SheetPrimitive.Trigger
export const SheetClose = SheetPrimitive.Close
export const SheetTitle = SheetPrimitive.Title
export const SheetDescription = SheetPrimitive.Description

/** A panel that slides in from the left (mobile navigation). Radix handles focus trap and Esc. */
export function SheetContent({
  className,
  children,
  ...props
}: ComponentProps<typeof SheetPrimitive.Content>) {
  return (
    <SheetPrimitive.Portal>
      <SheetPrimitive.Overlay className="fixed inset-0 z-50 bg-overlay backdrop-blur-sm animate-fade-in" />
      <SheetPrimitive.Content
        className={cn(
          'glass fixed inset-y-0 left-0 z-50 flex w-72 max-w-[85vw] flex-col rounded-r-xl bg-surface/90 p-4 shadow-modal',
          'animate-slide-in-left',
          className,
        )}
        {...props}
      >
        {children}
        <SheetPrimitive.Close
          className="absolute right-3 top-3 grid size-10 place-items-center rounded-md text-fg-secondary hover:bg-elevated hover:text-fg"
          aria-label="Close menu"
        >
          <X className="size-5" aria-hidden />
        </SheetPrimitive.Close>
      </SheetPrimitive.Content>
    </SheetPrimitive.Portal>
  )
}
