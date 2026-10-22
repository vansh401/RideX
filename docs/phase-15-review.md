# Phase 15 review: closing the gaps (real payments, real email, safety, money tools)

After Phase 14 an audit listed what was missing. Phase 15 builds it, with two decisions from the owner: **only free services**, and **email only, no SMS**. Setup of the real services is in [payments-and-email-setup.md](payments-and-email-setup.md); this document is the review: what changed, how it was checked, what went wrong, the honest limits, and how to defend the design.

## 1. What was built

| Gap | What exists now | Main places |
|---|---|---|
| Real payment gateway | **Razorpay** (free test mode) with a **test checkout** when no keys are set. Order, signed confirmation, signed webhook, circuit breaker. Online payments only ever fill the **wallet**; a ride the wallet cannot cover is paid with **Pay now** for exactly the shortfall | `payment-service/online/*`, `gateway/WalletOnlyGateway`, `useOnlinePayment.tsx` |
| Real email | Plain **SMTP** from environment (Gmail app password or Brevo, both free); Mailpit remains the default catcher. Notification service emails receipts, payment problems, refunds, complaint outcomes, driver approval | `notification-service/mail/*`, `application.properties`, compose |
| SOS | A button on the live ride for rider and driver: stored, shown to administrators at once (dashboard alert plus a Safety page), emailed to every administrator and to the rider's emergency contact with a live link | `ridex-app/safety/SafetyService`, `SafetyPanel.tsx`, `AdminSafetyPage.tsx` |
| Share my trip | An unguessable, expiring (24 h) link that shows the car on a map, the driver's first name, the vehicle and plate; **no login, no phone numbers** | `SafetyService.publicTrip`, `TripPage.tsx` |
| In-app chat | Rider and driver of one ride, from acceptance to shortly after the trip | `RideChatService`, `ChatPanel.tsx` |
| Offers page | Riders browse active offers they can still use; administrators can hide a code | `CouponService.offersFor`, `OffersPage.tsx` |
| Tips, receipts | One tip per ride from the wallet (the driver keeps all of it); a printable receipt page | `TipService`, `ReceiptService`, `ReceiptPage.tsx` |
| Cancellation fees | A rider who cancels after the driver arrived pays a small fee (wallet only; waived when the wallet is too low) | `CancellationFeeService` |
| Driver payouts | Request, administrator approves/records the transfer, or returns it; the money is held in the ledger meanwhile | `PayoutService`, `PayoutsSection.tsx`, `AdminPayoutsPage.tsx` |
| Incentive progress, busy areas | A progress bar toward the next daily bonus; a list of surging zones for drivers | `IncentiveService.progress`, `DriverInsightsController`, `IncentiveCard.tsx`, `BusyAreasCard.tsx` |
| Referrals | Every rider has a code; both sides are rewarded after the new rider's first paid ride | `ReferralService`, `WalletPage.tsx` |
| Admin screens | Finance (ledger summary, integrity check, refunds, manual adjustments, incentive run, surge now), Payouts, Safety | `AdminFinancePage.tsx` and friends |
| Complaint history | A Help page listing everything a rider or driver reported | `HelpPage.tsx` |
| Production | Kubernetes: optional `ridex-integrations` secret and mail settings; Compose: `.env`/secret files wired and documented | `infrastructure/k8s`, `docker-compose.yml` |

## 2. How it was checked

| Check | Result |
|---|---|
| Backend build (`mvnw verify`, real PostgreSQL/Kafka/Redis through Testcontainers) | all modules green, **840 tests**: core 495 (10 new safety tests), architecture **105** (new rules below), payment 85 (14 new integration + 3 provider tests), gateway 42, notification 28 (7 new), platform 36, pricing 27, analytics 22 |
| Frontend | `tsc`, `eslint`, vitest (79 tests), production build |
| New policy tests | the integrations secret must be **optional** and list only named keys; only the verified online-payment path may credit a wallet from outside; provider secrets/signatures/bodies are never logged; and the existing rule that every remote call uses a circuit breaker caught the Razorpay client before it shipped without one |
| Running system | the stack was rebuilt from the new code and started on the **existing** databases (the V3 payment and V18 core migrations applied cleanly to populated schemas). Through the gateway: an online top-up credited the wallet once although it was confirmed twice; referral code, offers, 404 for an unknown trip link, 401 for chat without a login and 400 for a webhook with a bad signature. In the browser: sign-in, the wallet with the test checkout (balance 500 to 750, statement updated), the invite card, the offers page |

## 3. What went wrong (and what it taught)

1. **Two exception hierarchies.** The new core classes threw the platform library's `ConflictException`/`ResourceNotFoundException`, which the core's own error handler does not know, so clean 404/409 answers became **500s**. The tests caught it; the fix was to use the core's own `common.*` exceptions. *Lesson: a shared library can have look-alike classes; check which handler owns them.*
2. **Append-only means append-only.** The first version of the admin adjustment tried to UPDATE a ledger description afterwards. The database trigger forbids that (by design). The description now includes the administrator from the start.
3. **The architecture tests protected me.** (a) `RazorpayProvider` made HTTP calls without a circuit breaker: the resilience rule failed the build and the provider now has one. (b) I put the new secret volume first in the pod spec, which shifted the index that the scale overlay patches by position; the policy test noticed the "core still claims the single disk" symptom. The volume is now appended last.
4. **A test bug, not a code bug:** an HMAC over an empty key throws in Java, so the "no webhook secret configured" test built its own forbidden signature. Fixed in the test.
5. **A missing profile.** The emergency-email test needed a rider *with a profile*; the other tests had a helper for that and I had not used it.
6. **A courtesy feature almost took a service out of rotation.** Adding the mail starter to the notification service also added Spring's *mail health check*, which pings the SMTP server. With no mail server the whole `/actuator/health` answered 503, and the existing health test caught it. In production that would mean: SMTP down, orchestrator pulls the notification pods. The mail health indicator is now disabled in both mail-using services. *Lesson: a dependency's auto-added health check is a decision about what may take you down.*

## 4. Honest limits

- **Ride sharing is still only the matching rule.** Wiring it into dispatch needs seat accounting and a driver who carries two rides, which changes the core state machine. That risk is not worth taking without its own phase, so it was deliberately not done.
- **No automatic card charge.** A real provider cannot be charged from our server without a saved-card mandate (not free and a larger compliance task). Rides are paid from the wallet; **Pay now** covers a shortfall. That is a real product choice, not a gap in the code.
- **Payouts are manual.** Real bank payouts are not free. RideX does the bookkeeping (hold, settle, return) and an administrator makes the transfer outside and records the reference.
- **The cancellation fee is waived when the wallet is empty.** A wallet can never go negative (the database forbids it), so a debt cannot be recorded. A future "owed" balance would fix it.
- **Razorpay was tested against its documented signature rules and with the test checkout, not against Razorpay's servers.** No account exists in this environment. The first real test-key payment is the final check; the setup guide lists the symptoms of the common mistakes.
- **Email was tested with a mocked sender and against the Mailpit catcher, not a real inbox** (no SMTP credentials here).
- **SMS is intentionally absent.** The SOS reaches administrators and an emergency contact by email only: a person who needs help right now should call the emergency number (112 in India); the app says so on the SOS dialog.
- **Browser-verified:** wallet, test checkout, invite card, offers. **Type-checked, linted, built and covered by API tests but not clicked through in a browser:** receipt, tip box, SOS and share dialogs, chat, the public trip page, the driver payouts/incentive/busy-areas cards, and the three new admin pages (an administrator login was not available to the author).

## 5. Interview questions from this phase

1. *Why does an online payment only ever credit the wallet?* A real provider cannot be charged from a server without a per-payment approval. Making the wallet the single landing place keeps one booking path (wallet first), one idempotent ledger entry, and refunds that are always wallet refunds.
2. *The browser and the webhook both report the same payment. How do you avoid crediting twice?* The provider's order id is unique, the row is locked (`for update`), and the ledger transaction has the key `online-<orderId>`: the second confirmation finds the row paid (or the key used) and does nothing.
3. *How do you know a payment confirmation is real?* An HMAC-SHA256 over `orderId|paymentId` with the key secret, compared in constant time. The webhook is signed over its exact raw body with a separate secret; without a configured secret, every webhook is refused.
4. *Why is the shared trip link safe to be public?* 192 random bits, expires in 24 hours, read-only, and it exposes a deliberately small view (first name, vehicle, plate, positions): no phone, email or payment. Unknown and expired links look identical (404).
5. *What if the mail server is down during an SOS?* The alert is committed first and shown to administrators at once; email is sent after the commit, best effort, and its failure is logged without the address or content. Safety never depends on email alone.
6. *How is a withdrawal kept from being spent twice?* The money moves into a hold account in the same ledger transaction as the request; paying settles it, rejecting returns it, each with its own idempotency key.
