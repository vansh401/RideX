import { Share2, ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { ShareLink } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent } from '@/components/ui/dialog'

/** One attempt to read the phone's position, never longer than a few seconds: an emergency must not wait for GPS. */
function currentPosition(): Promise<{ latitude: number; longitude: number } | null> {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve(null)
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: Number(p.coords.latitude.toFixed(6)), longitude: Number(p.coords.longitude.toFixed(6)) }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 4000, maximumAge: 15000 },
    )
  })
}

/** Share the trip with someone who worries, and the SOS button. Shown to the rider and the driver while a ride is on. */
export function SafetyPanel({ rideId }: { rideId: string }) {
  const [sosOpen, setSosOpen] = useState(false)
  const [sending, setSending] = useState(false)
  const [sent, setSent] = useState(false)
  const [link, setLink] = useState<ShareLink | null>(null)
  const [sharing, setSharing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function share() {
    setSharing(true)
    try {
      const { data } = await api.post<ShareLink>(`/api/rides/${rideId}/share`)
      setLink(data)
      if (navigator.share) {
        try {
          await navigator.share({ title: 'Follow my RideX trip', url: data.url })
          return
        } catch {
          // closed the share sheet: fall through to copying
        }
      }
      try {
        await navigator.clipboard.writeText(data.url)
        toast.success('Trip link copied. Anyone with it can follow this trip until it ends.')
      } catch {
        toast.info('Copy the link shown below and send it to someone you trust.')
      }
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setSharing(false)
    }
  }

  async function sendSos() {
    setSending(true)
    setError(null)
    try {
      const position = await currentPosition()
      await api.post(`/api/rides/${rideId}/sos`, position ?? {})
      setSent(true)
      setSosOpen(false)
      toast.success('SOS sent. Our safety team has been alerted.')
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      setSending(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <ShieldAlert className="size-4 text-accent" aria-hidden /> Safety
        </p>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" size="sm" loading={sharing} onClick={share}>
            <Share2 className="size-4" aria-hidden /> Share trip
          </Button>
          <Button variant="danger" size="sm" disabled={sent} onClick={() => setSosOpen(true)}>
            <ShieldAlert className="size-4" aria-hidden /> {sent ? 'SOS sent' : 'SOS'}
          </Button>
        </div>
        {link && (
          <p className="break-all text-xs text-fg-muted">
            Link (works for 24 hours): <span className="text-fg-secondary">{link.url}</span>
          </p>
        )}
        {sent && <Alert kind="info">Our safety team has been alerted and can see your trip. If you are in danger, also call 112 now.</Alert>}
      </CardContent>

      <Dialog open={sosOpen} onOpenChange={setSosOpen}>
        <DialogContent title="Send an SOS?" description="Our safety team is alerted at once with your trip and, if you allow it, your location. Your emergency contact is emailed a link to follow the trip, if you saved their email.">
          <div className="flex flex-col gap-3">
            <Alert kind="error">In an emergency, call 112 first. SOS tells RideX; it does not call the police.</Alert>
            {error && <Alert kind="error">{error}</Alert>}
            <Button variant="danger" size="lg" loading={sending} onClick={sendSos}>
              Send SOS now
            </Button>
            <Button variant="ghost" onClick={() => setSosOpen(false)}>
              Cancel
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </Card>
  )
}
