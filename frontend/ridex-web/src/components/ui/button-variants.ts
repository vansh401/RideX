import { cva } from 'class-variance-authority'

export const buttonVariants = cva(
  [
    'inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-md font-semibold',
    'transition-[background-color,box-shadow,transform,opacity] duration-(--dur-base) ease-(--ease-standard)',
    'active:translate-y-px disabled:pointer-events-none disabled:opacity-50',
  ],
  {
    variants: {
      variant: {
        primary: 'bg-accent text-on-accent hover:bg-accent-hover active:bg-accent-pressed',
        secondary:
          'border border-border-strong bg-elevated text-fg hover:border-fg-muted hover:bg-elevated/70',
        ghost: 'text-fg-secondary hover:bg-elevated hover:text-fg',
        danger: 'bg-danger text-canvas hover:brightness-110',
        // White high-emphasis button used on the landing hero, as in the reference
        inverse: 'bg-fg text-canvas hover:bg-white',
        link: 'h-auto p-0 text-accent underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-9 px-3 text-sm',
        md: 'h-11 px-5 text-sm',
        lg: 'h-12 px-6 text-base',
        // 44px minimum touch target
        icon: 'size-11',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)
