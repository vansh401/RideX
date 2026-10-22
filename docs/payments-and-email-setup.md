# Real payments and real email: setup (free)

Out of the box RideX uses a **test checkout** (no real money, no keys) and **Mailpit** (a local mail catcher). Both are replaced by the real services the moment you give RideX credentials. Nothing needs changing in the code. Credentials go in git-ignored files only.

| What | Free option used | You need |
|---|---|---|
| Online payments | **Razorpay test mode** (cards, UPI, netbanking; no real money moves) | a free Razorpay account: a test **Key Id** and **Key Secret** |
| Email | any SMTP server; **Gmail** (app password) or **Brevo** are free | an SMTP host, username and password |
| SMS | not used (by decision) | nothing |

Limits of free plans change; check the provider's current terms. At the time of writing a Gmail account sends a few hundred emails a day and Brevo's free plan about 300 a day, which is plenty here because RideX emails only the important things (receipts, payment problems, refunds, complaint outcomes, driver approval, SOS, sign-up and password reset).

## 1. Razorpay (online payments)

1. Create a free account at razorpay.com and stay in **Test mode** (switch at the top of the dashboard).
2. **Account & Settings → API Keys → Generate Test Key.** You get a **Key Id** (starts with `rzp_test_`) and a **Key Secret** (shown once: copy it now).
3. Put them where RideX reads them (Docker Compose):
   * in `infrastructure/docker-compose/.env`: `RAZORPAY_KEY_ID=rzp_test_xxxxxxxx` (the id is public: it is sent to the browser)
   * in the file `infrastructure/docker-compose/secrets/RAZORPAY_KEY_SECRET`: the secret only, no trailing newline. (`scripts\docker-init.ps1` creates the empty file.)
4. Restart payment: `docker compose up -d payment`. The payment service logs `Razorpay checkout enabled (test mode)`. In the app, **Wallet → Add money** now opens Razorpay's window.
5. Pay with Razorpay's test instruments (see Razorpay's "test cards" and "test UPI" pages: for example the card number `4111 1111 1111 1111` with any future expiry and any CVV, and a UPI id that Razorpay documents as "always succeeds").

### The webhook (optional but recommended)

The browser tells RideX a payment finished; the **webhook** is the provider telling RideX itself, in case the tab was closed right after paying. Either one alone credits the wallet, and both together credit it **once** (see `phase-15-review.md`).

* Razorpay needs a public URL, so for local use run a free tunnel (for example `cloudflared tunnel --url http://localhost:8000` or ngrok).
* **Settings → Webhooks → Add:** URL `https://<your-public-host>/api/payments/online/webhook`, a secret of your choice, events `payment.captured` and `order.paid`.
* Put that secret in `secrets/RAZORPAY_WEBHOOK_SECRET`. Without it, every webhook is refused (the safe default) and the browser confirmation still works.

### Going live later
Use `rzp_live_` keys instead. RideX prints `LIVE mode` at start-up so a mix-up is visible. Live payments need Razorpay account activation (KYC), which is their process, not RideX's.

## 2. Email (SMTP)

Pick one provider. All of them are used the same way.

**Gmail (free):** turn on 2-Step Verification, then create an **app password** at myaccount.google.com/apppasswords.

```
# infrastructure/docker-compose/.env
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=you@gmail.com
MAIL_AUTH=true
MAIL_STARTTLS=true
MAIL_FROM=you@gmail.com
```

and put the 16-character app password (only that) in `infrastructure/docker-compose/secrets/MAIL_PASSWORD`.

**Brevo (free):** create an account, then **SMTP & API → SMTP**: host `smtp-relay.brevo.com`, port `587`, your login as `MAIL_USERNAME`, the SMTP key as the password. Use a verified sender address as `MAIL_FROM`.

Then `docker compose up -d core notification`. Mail from sign-up (verification link), password reset, SOS alerts, receipts, refunds and so on now goes to real inboxes. To go back to Mailpit, remove the `MAIL_*` lines.

Email problems never break the app: a failed email is logged (without the address or the content) and the in-app notification is still there.

## 3. Without Docker Compose

Set the same names as environment variables before starting the services: `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM`, `FRONTEND_URL`.

## 4. Kubernetes

Put the non-secret values (`SPRING_MAIL_HOST`, `MAIL_FROM`, `RAZORPAY_KEY_ID`, ...) in the overlay's ConfigMap and the secrets in an **optional** Secret that the pods mount if it exists:

```bash
kubectl -n ridex create secret generic ridex-integrations \
  --from-literal=MAIL_PASSWORD='...' \
  --from-literal=RAZORPAY_KEY_SECRET='...' \
  --from-literal=RAZORPAY_WEBHOOK_SECRET='...'
```

If the secret does not exist the pods still start and use the test checkout and the default mail server.

## 5. What can go wrong

| Symptom | Cause |
|---|---|
| Wallet top-up shows "Test checkout" | no Razorpay keys are configured (the provider is `MOCK`) |
| "The payment could not be verified" | the key secret in `secrets/RAZORPAY_KEY_SECRET` does not belong to the key id |
| Payment succeeded at Razorpay but the wallet did not change | the browser closed before confirming and no webhook is configured; set the webhook (the wallet is then credited exactly once) |
| No emails arrive | wrong `MAIL_*`, or Gmail without an app password; check `docker logs ridex-notification` for `An email could not be sent` |
| Emails arrive in spam | free providers; use your own domain and SPF/DKIM when you move beyond a demo |
