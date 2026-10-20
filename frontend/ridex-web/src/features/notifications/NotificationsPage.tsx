import { Bell } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { AppNotification } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Pagination } from '@/components/brand/Pagination'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import { formatDateTime } from '@/lib/format'
import { announceNotificationsChanged } from '@/lib/notificationsEvents'
import { usePagedList } from '@/lib/usePagedList'
import { useSubscription } from '@/realtime/useRealtime'

export function NotificationsPage() {
  const navigate = useNavigate()
  const { data, error, loading, setPage, reload } = usePagedList<AppNotification>('/api/notifications', {}, 15)

  // A new one arrives while this page is open: show it at once
  useSubscription<AppNotification>('/user/queue/notifications', () => void reload())

  async function open(notification: AppNotification) {
    try {
      if (!notification.read) {
        await api.post(`/api/notifications/${notification.id}/read`)
        announceNotificationsChanged()
        await reload()
      }
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
    if (notification.rideId) void navigate(`/rides/${notification.rideId}`)
  }

  async function markAll() {
    try {
      await api.post('/api/notifications/read-all')
      announceNotificationsChanged()
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  const anyUnread = data?.content.some((n) => !n.read) ?? false

  return (
    <>
      <PageHeader
        eyebrow="Account"
        title="Notifications"
        actions={
          <Button variant="secondary" size="sm" disabled={!anyUnread} onClick={markAll}>
            Mark all as read
          </Button>
        }
      />
      {error && <Alert kind="error">{error}</Alert>}
      {loading && !data && <Skeleton className="h-48" />}
      {data?.content.length === 0 && (
        <EmptyState icon={Bell} title="Nothing yet" description="Updates about your rides will appear here." />
      )}
      {data && data.content.length > 0 && (
        <>
          <ul className="flex flex-col gap-2">
            {data.content.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  onClick={() => void open(n)}
                  className={cn(
                    'flex w-full items-start gap-3 rounded-lg border p-4 text-left transition-colors hover:border-fg-muted',
                    n.read ? 'border-border bg-surface' : 'border-accent/40 bg-accent-subtle',
                  )}
                >
                  <span
                    aria-hidden
                    className={cn('mt-2 size-2 shrink-0 rounded-full', n.read ? 'bg-transparent' : 'bg-accent')}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block font-semibold text-fg">
                      {n.title}
                      {!n.read && <span className="sr-only"> (unread)</span>}
                    </span>
                    <span className="block text-sm text-fg-secondary">{n.body}</span>
                    <time className="tabular mt-1 block text-xs text-fg-muted" dateTime={n.createdAt}>
                      {formatDateTime(n.createdAt)}
                    </time>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <Pagination page={data.page} size={data.size} totalElements={data.totalElements} totalPages={data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  )
}
