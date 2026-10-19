import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { StarRating } from './StarRating'

/** After a completed trip each side rates the other once. `existing` is the stars already given. */
export function RatingForm({
  rideId,
  role,
  existing,
  onSaved,
}: {
  rideId: string
  role: 'RIDER' | 'DRIVER'
  existing: number | null
  onSaved: () => Promise<void>
}) {
  const [stars, setStars] = useState(0)
  const [comment, setComment] = useState('')
  const [busy, setBusy] = useState(false)
  const who = role === 'RIDER' ? 'driver' : 'rider'

  if (existing !== null) {
    return (
      <Card>
        <CardContent>
          <p className="text-fg">
            You rated this {who} <span className="font-semibold">{existing} / 5</span>. Thank you!
          </p>
        </CardContent>
      </Card>
    )
  }

  async function submit() {
    setBusy(true)
    try {
      await api.post(`/api/rides/${rideId}/ratings`, { stars, comment: comment.trim() || null })
      toast.success('Thanks for rating')
      await onSaved()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="font-semibold text-fg">How was your {who}?</p>
        <StarRating value={stars} onChange={setStars} disabled={busy} />
        <Input
          aria-label="Comment (optional)"
          placeholder="Add a comment (optional)"
          maxLength={500}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
        <Button disabled={stars === 0} loading={busy} onClick={submit}>
          Submit rating
        </Button>
      </CardContent>
    </Card>
  )
}
