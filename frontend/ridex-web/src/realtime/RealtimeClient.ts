import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

/**
 * A WebSocket (STOMP) connection that survives bad networks and expiring tokens.
 *
 * What it does for the rest of the app:
 * - connect with the CURRENT access token (refreshed first if it is about to expire);
 * - keep a registry of "who wants which destination", and subscribe all of them again after every
 *   reconnect (a new connection starts with no subscriptions at all);
 * - reconnect by itself after a drop, waiting longer after each failure (exponential backoff with
 *   jitter) so a server that just restarted is not hit by every browser at the same second;
 * - reconnect on its own shortly BEFORE the token expires, because the server checks the token on every
 *   frame and would otherwise refuse the connection mid-ride.
 */

export type ConnectionState = 'offline' | 'connecting' | 'live' | 'reconnecting'

export interface TokenInfo {
  token: string
  /** When the token expires, in milliseconds since 1970. */
  expiresAtMs: number
}

export interface RealtimeOptions {
  url: string
  /** Returns a usable token, refreshing the session first if needed. null means "not logged in". */
  getToken: () => Promise<TokenInfo | null>
}

type Handler = (body: unknown) => void

const BASE_DELAY_MS = 1_000
const MAX_DELAY_MS = 30_000
/** Reconnect this long before the token expires. */
const RENEW_BEFORE_EXPIRY_MS = 60_000

/**
 * How long to wait before reconnect attempt number `attempt` (0 for the first retry):
 * 1 s, 2 s, 4 s, 8 s ... up to 30 s, each with up to 25% random jitter added.
 * `random` is injectable so the maths can be tested.
 */
export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const exponential = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * 2 ** Math.max(0, attempt))
  return Math.round(exponential + exponential * 0.25 * random())
}

/** Reads the expiry (seconds since 1970) from a JWT without verifying it: the server verifies. */
export function tokenExpiryMs(jwt: string): number | null {
  try {
    const payload = jwt.split('.')[1]
    if (!payload) return null
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const exp = (JSON.parse(json) as { exp?: number }).exp
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}

export class RealtimeClient {
  private client: Client | null = null
  private readonly handlers = new Map<number, { destination: string; handler: Handler }>()
  private readonly active = new Map<number, StompSubscription>()
  private readonly stateListeners = new Set<(state: ConnectionState) => void>()
  private nextId = 1
  private attempt = 0
  private retryTimer: number | undefined
  private renewTimer: number | undefined
  private stopped = true
  private current: ConnectionState = 'offline'

  private readonly options: RealtimeOptions

  constructor(options: RealtimeOptions) {
    this.options = options
  }

  get state(): ConnectionState {
    return this.current
  }

  onState(listener: (state: ConnectionState) => void): () => void {
    this.stateListeners.add(listener)
    return () => {
      this.stateListeners.delete(listener)
    }
  }

  start(): void {
    if (!this.stopped) return
    this.stopped = false
    void this.open()
  }

  stop(): void {
    this.stopped = true
    window.clearTimeout(this.retryTimer)
    window.clearTimeout(this.renewTimer)
    this.closeSocket()
    this.setState('offline')
  }

  /**
   * Listen to a destination. Safe to call before the connection exists: the subscription is made
   * as soon as it does, and again after every reconnect. Returns the function that stops listening.
   */
  subscribe(destination: string, handler: Handler): () => void {
    const id = this.nextId++
    this.handlers.set(id, { destination, handler })
    this.attach(id)
    return () => {
      this.handlers.delete(id)
      this.active.get(id)?.unsubscribe()
      this.active.delete(id)
    }
  }

  /** Sends a JSON message. Returns false when not connected (the caller can use REST instead). */
  send(destination: string, body: unknown): boolean {
    if (this.current !== 'live' || !this.client?.connected) return false
    this.client.publish({
      destination,
      body: JSON.stringify(body),
      headers: { 'content-type': 'application/json' },
    })
    return true
  }

  // ---------------------------------------------------------------------------------------

  private setState(next: ConnectionState): void {
    if (next === this.current) return
    this.current = next
    this.stateListeners.forEach((listener) => listener(next))
  }

  private attach(id: number): void {
    const entry = this.handlers.get(id)
    if (!entry || !this.client?.connected || this.active.has(id)) return
    const subscription = this.client.subscribe(entry.destination, (message: IMessage) => {
      let body: unknown = message.body
      try {
        body = JSON.parse(message.body)
      } catch {
        // not JSON: hand over the raw text
      }
      entry.handler(body)
    })
    this.active.set(id, subscription)
  }

  private async open(): Promise<void> {
    this.setState(this.attempt === 0 ? 'connecting' : 'reconnecting')
    let info: TokenInfo | null
    try {
      info = await this.options.getToken()
    } catch {
      info = null
    }
    if (this.stopped) return
    if (!info) {
      this.scheduleRetry()
      return
    }

    const client = new Client({
      brokerURL: this.options.url,
      connectHeaders: { Authorization: `Bearer ${info.token}` },
      // "Are you still there?" both ways every 10 s. A connection that silently died (phone went into a
      // tunnel) is noticed within seconds instead of staying "open" forever.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      reconnectDelay: 0, // we do our own reconnecting, with a fresh token each time
    })

    client.onConnect = () => {
      this.attempt = 0
      this.setState('live')
      this.handlers.forEach((_entry, id) => this.attach(id))
      // Come back with a fresh token shortly before this one expires
      window.clearTimeout(this.renewTimer)
      const wait = Math.max(5_000, info.expiresAtMs - Date.now() - RENEW_BEFORE_EXPIRY_MS)
      this.renewTimer = window.setTimeout(() => this.reconnectNow(), wait)
    }
    // An ERROR frame (token refused...) or a closed socket both end here
    client.onStompError = () => this.handleLost()
    client.onWebSocketClose = () => this.handleLost()

    this.client = client
    client.activate()
  }

  private handleLost(): void {
    if (this.stopped) return
    this.closeSocket()
    this.setState('reconnecting')
    this.scheduleRetry()
  }

  private scheduleRetry(): void {
    window.clearTimeout(this.retryTimer)
    const delay = backoffDelay(this.attempt)
    this.attempt += 1
    this.retryTimer = window.setTimeout(() => void this.open(), delay)
  }

  private reconnectNow(): void {
    if (this.stopped) return
    this.closeSocket()
    this.attempt = 0
    void this.open()
  }

  private closeSocket(): void {
    this.active.clear() // the old connection's subscriptions are gone with it
    const old = this.client
    this.client = null
    if (old) {
      old.onWebSocketClose = () => undefined
      old.onStompError = () => undefined
      void old.deactivate()
    }
  }
}
