import { useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Input } from '@/components/ui/input'

/**
 * Asks before something consequential. With `askReason`, a written reason is required and passed
 * to `onConfirm` (used for rejecting a driver, suspending a user, cancelling a ride).
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  danger = false,
  askReason = false,
  reasonRequired = true,
  onConfirm,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  confirmLabel: string
  danger?: boolean
  askReason?: boolean
  reasonRequired?: boolean
  onConfirm: (reason: string) => Promise<void> | void
  children?: ReactNode
}) {
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const blocked = askReason && reasonRequired && reason.trim() === ''

  async function confirm() {
    setBusy(true)
    try {
      await onConfirm(reason.trim())
      onOpenChange(false)
      setReason('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} description={description}>
        {children}
        {askReason && (
          <div className="mb-5 flex flex-col gap-1.5">
            <Label htmlFor="confirm-reason">Reason{reasonRequired ? '' : ' (optional)'}</Label>
            <Input id="confirm-reason" value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} autoFocus />
          </div>
        )}
        <div className="flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onOpenChange(false)}>
            Back
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} disabled={blocked} loading={busy} onClick={confirm}>
            {confirmLabel}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
