import { cva } from 'class-variance-authority'

// Tactile controls: a lit top edge, a small press-down on :active, no bounce. Only transform,
// opacity and shadow are animated.
export const buttonVariants = cva(
  [
    'inline-flex shrink-0 select-none items-center justify-center gap-2 whitespace-nowrap rounded-md font-semibold',
    'transition-[background-color,border-color,box-shadow,transform,opacity,color] duration-(--dur-base) ease-(--ease-standard)',
    'active:scale-[0.97] active:duration-(--dur-fast) disabled:pointer-events-none disabled:opacity-50',
  ],
  {
    variants: {
      variant: {
        primary: [
          'shine bg-gradient-to-b from-accent-hover to-accent text-on-accent',
          'shadow-[inset_0_1px_0_rgb(255_255_255/0.45),0_1px_2px_rgb(0_0_0/0.4)]',
          'hover:shadow-glow hover:brightness-105 active:brightness-95',
        ],
        secondary: [
          'border border-border-strong bg-elevated/80 text-fg panel',
          'hover:border-fg-muted/60 hover:bg-elevated active:bg-surface',
        ],
        ghost: 'text-fg-secondary hover:bg-white/[0.06] hover:text-fg active:bg-white/[0.09]',
        danger: [
          'bg-danger text-canvas shadow-[inset_0_1px_0_rgb(255_255_255/0.3)]',
          'hover:brightness-110 active:brightness-95',
        ],
        // White high-emphasis button used on the landing hero
        inverse: 'bg-fg text-canvas shadow-[inset_0_-2px_0_rgb(0_0_0/0.12)] hover:bg-white',
        link: 'h-auto p-0 text-accent underline-offset-4 hover:underline active:scale-100',
      },
      size: {
        sm: 'h-9 px-3.5 text-sm',
        md: 'h-11 px-5 text-sm',
        lg: 'h-12 px-6 text-base',
        // 44px minimum touch target
        icon: 'size-11',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)
