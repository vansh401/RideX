# Real time with WebSocket and STOMP, explained from zero

This explains every idea used in Phase 5, in the order you would meet them. Each part ends with
"where in our code", so you can open the file and see it.

---

## 1. The problem: how does the rider's screen learn that the driver accepted?

### Option A: polling ("are we there yet?")
The browser asks the server again and again: `GET /api/rides/123` every 4 seconds.

* Simple and it works everywhere. This is what Phase 3 did.
* Wasteful: 1,000 riders waiting means 250 requests per second, almost all answering "nothing changed".
* Slow: news arrives up to 4 seconds late. A driver's moving dot jumps instead of gliding.

### Option B: push
The server tells the browser the instant something happens. That needs a connection the server can talk
through whenever it wants. **WebSocket** is exactly that.

## 2. WebSocket

An HTTP request is a letter: you send it, you get one reply, the conversation is over. A **WebSocket** is a
phone call: after one "handshake" (an HTTP request that says "let us upgrade to WebSocket"), the line stays
open and **either side can speak at any moment**, with almost no overhead per message.

Costs of a phone call:
* The server keeps one connection open per user (memory and a socket each). 10,000 online users = 10,000 open
  connections.
* Connections drop (tunnels, Wi-Fi changes, sleeping laptops), so you must detect and repair them.
* Authentication works differently (section 4), and so does scaling (section 9).

## 3. STOMP: giving the phone call some structure

Raw WebSocket only carries bytes. You would have to invent your own rules: "which messages are for whom?",
"how do I say what I want to listen to?". **STOMP** is a small text protocol on top that provides them. A
STOMP message is a *frame*: a command, headers, and a body.

```
CONNECT                         client -> server  (log in)
Authorization:Bearer eyJ...

SUBSCRIBE                       client -> server  ("send me everything for this destination")
id:sub-0
destination:/user/queue/offers

SEND                            client -> server  (publish to a destination)
destination:/app/driver/location
content-type:application/json

{"latitude":12.97,"longitude":77.59}

MESSAGE                         server -> client  (something arrived)
destination:/user/queue/offers
subscription:sub-0

{"rideId":"...","pickupAddress":"..."}
```

A **destination** is like a mailbox address or a radio channel. Ours:

| Destination | Direction | Meaning |
|---|---|---|
| `/app/driver/location` | client sends | a driver streams their position |
| `/user/queue/ride-updates` | client listens | "one of **my** rides changed" |
| `/user/queue/offers` | client listens | "a ride is offered to **me**" / "that offer is gone" |
| `/user/queue/notifications` | client listens | "I have a new notification" |
| `/user/queue/errors` | client listens | "a message I sent was refused" |
| `/topic/rides/{id}/location` | client listens | the driver's position on **this** ride |

Prefix meaning (configured in `RealtimeConfig`):
* `/app/...` : messages that go to **our Java code** (`@MessageMapping`).
* `/topic/...` : a **topic** is a shared channel: everyone subscribed receives every message.
* `/queue/...` and `/user/queue/...` : a **private queue**. When our server sends to user X's
  `/queue/offers`, Spring delivers it only to the sessions of user X, even though every browser subscribed to
  the same name `/user/queue/offers`. Spring rewrites the name behind the scenes using the logged-in user.

**Why per-user queues for ride updates and a topic for location?** Ride updates are about a person, so a
private queue is natural and safe. Location is about a ride, with exactly the participants listening. Both
patterns exist in real systems; using each where it fits is the lesson.

Code: [`RealtimeConfig`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/realtime/RealtimeConfig.java).

## 4. Authentication on a WebSocket (Step 5.1)

For normal requests, Spring Security reads the `Authorization` header on **every** request. A WebSocket is one
long connection, so the rules change:

1. **A browser cannot add headers to the WebSocket handshake.** (The JavaScript `WebSocket` API has no way to.)
   So the token goes in the **CONNECT frame** header instead. The handshake URL `/ws` is open
   (`permitAll`); the STOMP login happens one step later.
2. We verify that token exactly like the HTTP filter does: signature, expiry, issuer **and the Redis
   revocation list** from Phase 4. Wrong or missing token: the server answers an `ERROR` frame and closes. The
   token is never put in the URL (URLs end up in logs).
3. Because the connection lives long, **every later frame re-checks** that the token has not expired or been
   revoked. If an admin suspends a driver, the next thing their app sends is refused.
4. **Origin check.** Browsers always send an `Origin` header for WebSockets and scripts cannot fake it. Only
   our own page addresses (`ridex.websocket.allowed-origins`) may connect. This blocks "cross-site WebSocket
   hijacking" where another website tries to use your logged-in browser.

Code: [`StompSecurityInterceptor`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/realtime/StompSecurityInterceptor.java)
(an *interceptor* is code that sees every incoming frame before anything else does).

### Authorization: who may listen to what?
Authentication says *who you are*. Authorization says *what you may do*. We check each frame:

* `SUBSCRIBE /user/queue/...` : allowed for everyone signed in (it is private to them anyway).
* `SUBSCRIBE /topic/rides/{id}/location` : only the **rider or driver of that ride** (or an admin). Otherwise any
  rider could watch any driver by guessing a ride id.
* `SUBSCRIBE` anything else: refused.
* `SEND /app/driver/location` : only role DRIVER. `SEND` anywhere else: refused (so nobody can publish
  directly into a topic).

## 5. Driver location streaming (Step 5.2)

```
driver's phone --SEND /app/driver/location--> server
                                               |-- Redis GEO index (matching sees the driver)   [Phase 4]
                                               '-- is this driver on a trip?
                                                      yes -> publish to /topic/rides/{id}/location
rider's browser <------MESSAGE-------------------------'
```

* One open connection, tiny frames, no HTTP headers and no authentication work per update.
* We still keep the REST endpoint as a **fallback**: if the WebSocket is down the driver app falls back to it.
* A buggy client could send hundreds of positions per second, so extra ones are dropped (max 20 per 10 s per
  driver, counted in Redis).
* A rider who opens the page *after* the driver started moving needs the last position at once:
  `GET /api/rides/{id}/driver-location` (read from Redis), then the live stream continues.

Code: [`DriverLocationStream`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/realtime/DriverLocationStream.java).

## 6. Pushing ride status (Step 5.3)

Whenever a ride changes status, the ride module publishes a Spring event, `RideStatusChanged`. How:
`Ride.moveTo()` (the single place where status changes) records the change; when the ride is saved Spring
Data publishes it. The ride code knows nothing about WebSockets.

`RideRealtimeListener` reacts **after the database commit** and pushes to the rider and driver:

```json
{ "rideId": "…", "status": "DRIVER_ACCEPTED", "previousStatus": "DRIVER_ASSIGNED", "at": "…" }
```

Two deliberate choices:
* **After commit, never before.** Otherwise a rider could be told "your driver accepted" and then the
  database transaction fails and rolls back.
* **A hint, not the data.** The push says only "ride X is now Y". The browser then calls
  `GET /api/rides/X`. What a rider may see differs from what a driver may see (phone numbers appear only
  after acceptance), and REST is the one place that decides that. One source of truth.

## 7. Ride offers with timeout (Step 5.4)

When a rider requests a ride, the nearest free driver (Redis GEO, Phase 4) is *offered* it for 30 seconds:

```
rider requests -> driver A offered (push to A: /user/queue/offers)
   A accepts            -> ride continues
   A declines           -> A's screen drops the offer (push), driver B is offered next
   A ignores for 30 s   -> a background job expires it, B is offered next
   rider cancels        -> the offer disappears from A's screen
   nobody answers for 2 min -> the ride is cancelled: "no driver found"
```

New in Phase 5 is the **instant delivery** and **instant withdrawal** of the offer on the driver's screen. The
retry logic (try A, then B; remember who declined so nobody is asked twice) already existed from Phase 3 and
runs every 5 seconds in `RideDispatcher`.

## 8. Notifications: stored AND live (Step 5.5)

A notification such as "Your driver has arrived" is both:
* **stored** in PostgreSQL (`notifications` table): still there after a reload, or if you were offline;
* **pushed** on `/user/queue/notifications`: appears as a toast at once, with the bell count going up.

Why both? A WebSocket message is *fire and forget*: if the browser is closed, the message is gone. The stored
row is the memory. The page loads the unread count over REST every time it connects, so nothing is missed.

`RideNotificationListener` decides who is told what (the only place with the wording). It also runs after
commit, and the notification is saved in its **own** transaction: a notification problem must never roll back
a ride. Approving or rejecting a driver application notifies the driver too.

## 9. Scaling WebSockets (Step 5.7)

Spring's built-in broker keeps subscriptions in **the memory of one server**. A browser is connected to just
one server. With two servers:

```
rider -> connected to server B          driver accepts via server A
                                        A produces "DRIVER_ACCEPTED" ... but only A's own browsers hear it. B never knows.
```

**Solution: a shared fan-out through Redis publish/subscribe.** Producers never send directly to the browsers.
They PUBLISH to one Redis channel (`realtime:events`). **Every** server is SUBSCRIBED to it, receives each
message, and delivers it to the browsers connected to *it*; servers with no matching browser ignore it.

```
any server --PUBLISH--> Redis channel --> server A (delivers to its browsers)
                                      --> server B (delivers to its browsers)
                                      --> server C (nobody matches: ignores)
```

* The same code path is used with one server or fifty, so it cannot rot.
* No sticky sessions needed (a load balancer does not have to send a user back to the same server for
  messages; the user's own connection is simply wherever it was opened).
* **Limits:** Redis pub/sub does not store messages; a server that is down misses them; delivery is
  "at most once". That is why anything important is also stored (notifications) or re-fetchable (REST).
* **Alternative:** a real message broker (RabbitMQ or ActiveMQ) with Spring's *STOMP broker relay*: more
  features (persistence, acknowledgements), more infrastructure. Redis was already there.
* If Redis is down, a server delivers to **its own** browsers only and logs a warning.

Code: [`RedisRealtimeBridge`](../backend/ridex-app/src/main/java/com/ridex/ridex_app/realtime/RedisRealtimeBridge.java).
Try it: `docker exec -it ridex-redis redis-cli SUBSCRIBE realtime:events`, then request a ride and watch the
messages go by.

## 10. The React side (Step 5.6)

One `RealtimeClient` for the whole signed-in area (`RealtimeProvider` inside the app shell). It handles:

* **Connect** with the current access token, refreshing it first if it is about to expire.
* **Heartbeats**: both sides send a tiny "still here" every 10 seconds. A connection that silently died (phone
  in a tunnel) is noticed within seconds instead of staying "open" forever.
* **Reconnect with exponential backoff and jitter.** After a failure wait 1 s, then 2, 4, 8 ... up to 30 s,
  plus up to 25% random extra time. Why: if the server restarts and 10,000 browsers all retry every second, they
  hammer it exactly when it is weakest (a *thundering herd*). Growing delays spread the load, and randomness
  stops them marching in step.
* **Resubscribe after every reconnect.** A new connection begins with **no** subscriptions. The client keeps a
  registry of who wants what and re-subscribes all of it.
* **Renew before the token expires.** The server checks the token on every frame; the client reconnects with a
  fresh one about a minute before expiry, so a ride is not interrupted mid-trip.
* **Cleanup.** `useSubscription` removes the listener when a screen unmounts. Leaving a page never leaves a
  zombie listener running.
* **Graceful fallback.** While live, screens refresh slowly (a safety net). When the connection is down they
  go back to asking every few seconds. The small dot in the header shows Live / Reconnecting.

Code: `frontend/ridex-web/src/realtime/` and the screens that use it (`RideDetailPage`, `DriverDashboard`,
`NotificationBell`, `NotificationsPage`).

## 11. Glossary

* **Polling**: asking repeatedly. **Push**: the server tells you.
* **WebSocket**: a long-lived two-way connection. **Handshake**: the HTTP request that starts it.
* **STOMP**: a small protocol of frames (CONNECT, SUBSCRIBE, SEND, MESSAGE) and destinations on top of WebSocket.
* **Destination**: a named channel. **Topic**: shared by all subscribers. **Queue**: private to one consumer/user.
* **Broker**: the part that routes messages to subscribers. **Simple broker**: Spring's in-memory one.
* **Interceptor**: code that sees every message before the rest of the application.
* **Heartbeat**: a periodic "I am still here" signal.
* **Backoff / jitter**: growing waits between retries / a little randomness added to them.
* **Thundering herd**: everyone retrying at once.
* **Pub/sub**: publishers send to a channel, every subscriber receives it; nobody knows each other.
* **At most once**: a message is delivered zero or one times, never twice, but may be lost.
* **Origin**: the website a request comes from; browsers attach it and it cannot be forged by scripts.

## 12. See it yourself

* Browser DevTools, Network tab, filter **WS**, click `/ws`, open **Messages**: you will see the `CONNECT`,
  `SUBSCRIBE`, `MESSAGE` frames live.
* Open a rider in one browser and a driver in another (a private window) and walk through a ride. Watch the
  header dot and the notification bell.
* Stop the backend for 20 seconds: the dot turns amber ("Reconnecting"), and returns by itself when you
  restart it.
