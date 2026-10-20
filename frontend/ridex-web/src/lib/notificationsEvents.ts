// The bell in the header and the notifications page both show "unread". When the page marks things as
// read, it tells the bell through this tiny event so the two never disagree.
const EVENT = 'ridex:notifications-changed'

export function announceNotificationsChanged(): void {
  window.dispatchEvent(new Event(EVENT))
}

export function onNotificationsChanged(listener: () => void): () => void {
  window.addEventListener(EVENT, listener)
  return () => window.removeEventListener(EVENT, listener)
}
