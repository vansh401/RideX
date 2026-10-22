import { MessageCircle, Send } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { ChatMessage } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatDateTime } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

/** A small conversation between the rider and the driver of this ride (only they can read it). Refreshes every few seconds. */
export function ChatPanel({ rideId, otherName }: { rideId: string; otherName: string }) {
  const { data, refresh } = usePolling(async () => (await api.get<ChatMessage[]>(`/api/rides/${rideId}/messages`)).data, { intervalMs: 4000 })
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const end = useRef<HTMLDivElement>(null)
  const count = data?.length ?? 0

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'nearest' })
  }, [count])

  async function send(event: FormEvent) {
    event.preventDefault()
    const body = text.trim()
    if (!body) return
    setSending(true)
    try {
      await api.post(`/api/rides/${rideId}/messages`, { body })
      setText('')
      await refresh()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setSending(false)
    }
  }

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <MessageCircle className="size-4 text-accent" aria-hidden /> Chat with {otherName}
        </p>
        <div className="flex max-h-56 min-h-16 flex-col gap-2 overflow-y-auto rounded-md bg-black/20 p-3" aria-live="polite" aria-label="Messages">
          {count === 0 && <p className="text-sm text-fg-muted">No messages yet. Say hello, or tell them where to find you.</p>}
          {data?.map((m) => (
            <div key={m.id} className={`flex flex-col ${m.mine ? 'items-end' : 'items-start'}`}>
              <p className={`max-w-[85%] rounded-lg px-3 py-2 text-sm ${m.mine ? 'bg-accent text-on-accent' : 'bg-white/10 text-fg'}`}>{m.body}</p>
              <time className="mt-0.5 text-[11px] text-fg-muted" dateTime={m.createdAt}>
                {formatDateTime(m.createdAt)}
              </time>
            </div>
          ))}
          <div ref={end} />
        </div>
        <form onSubmit={send} className="flex gap-2">
          <input
            aria-label="Message"
            value={text}
            maxLength={500}
            onChange={(e) => setText(e.target.value)}
            placeholder="Write a message"
            className="h-11 flex-1 rounded-md border border-border-strong bg-canvas/70 px-3 text-sm"
          />
          <Button type="submit" size="icon" aria-label="Send message" loading={sending} disabled={!text.trim()}>
            <Send className="size-4" aria-hidden />
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
