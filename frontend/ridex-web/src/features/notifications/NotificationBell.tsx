import { Bell } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { AppNotification } from '@/api/types'
import { onNotificationsChanged } from '@/lib/notificationsEvents'
import { useRealtime, useSubscription } from '@/realtime/useRealtime'

/** The bell in the header: unread count, and a toast the moment a new notification arrives. */
export function NotificationBell() {
  const { state } = useRealtime()
  const [unread, setUnread] = useState(0)

  const load = useCallback(async () => {
    try {
      const { data } = await api.get<{ count: number }>('/api/notifications/unread-count')
      setUnread(data.count)
    } catch {
      // the bell is a convenience; the page itself still works
    }
  }, [])

  // Load on first show, after every (re)connection (to catch anything missed while offline), and when the
  // notifications page changed something
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- a request is starting
    void load()
    return onNotificationsChanged(() => void load())
  }, [load, state])

  useSubscription<AppNotification>('/user/queue/notifications', (notification) => {
    setUnread((count) => count + 1)
    toast(notification.title, { description: notification.body })
  })

  return (
    <Link
      to="/notifications"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
      className="relative grid size-11 place-items-center rounded-md text-fg-secondary hover:bg-elevated hover:text-fg"
    >
      <Bell className="size-5" aria-hidden />
      {unread > 0 && (
        <span className="tabular absolute right-1 top-1 grid min-w-5 place-items-center rounded-full bg-accent px-1 text-xs font-bold text-on-accent">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  )
}
