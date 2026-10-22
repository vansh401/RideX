import { CircleCheckIcon, InfoIcon, Loader2Icon, OctagonXIcon, TriangleAlertIcon } from 'lucide-react'
import type { CSSProperties } from 'react'
import { Toaster as Sonner, type ToasterProps } from 'sonner'

/**
 * Toasts as small glass windows. Structure follows the shadcn/ui "sonner" component (icons per kind,
 * colours through CSS variables); RideX is dark-only, so there is no theme switch.
 */
export function Toaster(props: ToasterProps) {
  return (
    <Sonner
      theme="dark"
      position="top-right"
      className="toaster group"
      icons={{
        success: <CircleCheckIcon className="size-4 text-success" />,
        info: <InfoIcon className="size-4 text-info" />,
        warning: <TriangleAlertIcon className="size-4 text-warning" />,
        error: <OctagonXIcon className="size-4 text-danger" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      toastOptions={{
        classNames: { toast: 'glass glass-float !rounded-lg' },
        style: { color: 'var(--text-primary)' },
      }}
      style={
        {
          '--normal-bg': 'transparent',
          '--normal-text': 'var(--text-primary)',
          '--normal-border': 'var(--glass-edge)',
          '--border-radius': 'var(--radius-lg)',
        } as CSSProperties
      }
      {...props}
    />
  )
}
