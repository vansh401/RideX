# Real payments and real email: setup (free)

Out of the box RideX uses a **test checkout** (no real money, no keys) and **Mailpit** (a local mail catcher). Both are replaced by the real services the moment you give RideX credentials. Nothing changes in the code: **keys only go in git-ignored files**.

| What | Free option | You need |
|---|---|---|
| Online payments | **Stripe** (test mode; Razorpay is also supported as an alternative, section 4) | a free Stripe account: a test **publishable key**, a test **secret key** (and optionally a **webhook secret**) |
| Email | any SMTP server: **Gmail** (app password) or **Brevo** | an SMTP host, username and password |
| SMS | not used (by decision) | nothing |

RideX picks the payment provider by which keys exist: Stripe if `STRIPE_SECRET_KEY` is set, otherwise Razorpay if its keys are set, otherwise the test checkout. The payment service says which one it chose in its log at start-up.

## 1. Stripe (online payments)

How it works here: when a rider pays, RideX sends them to **Stripe's own hosted page**. Card details are typed on Stripe's page, never on RideX. Stripe then brings the rider back, and RideX's server **asks Stripe** whether the payment really happened before it credits the wallet (nothing the browser says is trusted).

**Account and keys**
1. Go to **stripe.com** and create an account (email + password, then confirm the email Stripe sends).
2. Stay in **test mode** (the dashboard shows a "Test mode" or "Sandbox" indicator or toggle). You do **not** need to activate the business account for test mode; skip any "activate your account" prompts.
3. Open **Developers → API keys.** You will see:
   * **Publishable key**: starts with `pk_test_`. Public.
   * **Secret key**: starts with `sk_test_`. Click **Reveal**. This one is secret.
4. Test payments use Stripe's test cards, for example **4242 4242 4242 4242**, any future expiry date, any 3-digit CVC and any postcode. No real money moves.

**Where the values go** (Docker Compose, from `F:\RideX\ridex`):

1. Open `infrastructure\docker-compose\.env` (git-ignored) and add:

```
STRIPE_PUBLISHABLE_KEY=pk_test_xxxxxxxxxxxxxxxx
FRONTEND_URL=http://localhost:3000
```

`FRONTEND_URL` is where Stripe sends the rider back to; it must be the address you open the website at (3000 for Docker, 5173 for the dev server).

2. Write the secret key into its file. Run this in your own PowerShell (it writes the value with no trailing newline, which matters):

```powershell
[IO.File]::WriteAllText("F:\RideX\ridex\infrastructure\docker-compose\secrets\STRIPE_SECRET_KEY", "sk_test_PASTE-YOURS-HERE", (New-Object System.Text.UTF8Encoding($false)))
```

3. Recreate the payment service and check its log:

```powershell
cd F:\RideX\ridex\infrastructure\docker-compose
docker compose up -d --force-recreate payment
docker logs ridex-payment 2>&1 | Select-String "Stripe"
```

You should see `Stripe checkout enabled (test mode)`. In the website, **Wallet → Add money** now sends you to Stripe's page. After paying you return to the wallet and the balance has changed.

**The webhook (optional safety net).** The return from Stripe's page already confirms the payment. The webhook only matters if someone pays and closes the tab before returning. It needs Stripe to reach your computer, so for local use the easy way is Stripe's free command-line tool: install the Stripe CLI, run `stripe login`, then `stripe listen --forward-to localhost:8000/api/payments/online/webhook`. It prints a **webhook signing secret** (`whsec_...`): write it into `secrets\STRIPE_WEBHOOK_SECRET` the same way as above and recreate `payment`. Without a webhook secret every webhook is refused (the safe default) and the return-page confirmation still works. Either path credits the wallet **once**.

**Good to know**
* Stripe enforces a minimum charge per currency. If a very small top-up is rejected, try a larger amount; RideX's own minimum is ₹10.
* Test mode works for any account. **Live** payments in INR need Stripe to approve your account for your country; that is Stripe's process, not RideX's. RideX prints `LIVE mode` at start-up if you ever use `sk_live_` keys.
* If you rotate or delete a key in the Stripe dashboard, write the new one into the file and recreate `payment`.

## 2. Email (SMTP), step by step

You need an SMTP server that will accept mail from RideX. Two free choices follow; pick **one**.

### Option A: Gmail (simplest if you already have a Gmail account)
1. Sign in at **myaccount.google.com → Security.**
2. Turn on **2-Step Verification** (an app password can only be created with it on). Follow Google's prompts (phone number, etc.).
3. Open **myaccount.google.com/apppasswords.** Name the app `RideX` and click **Create.** Google shows a **16-character app password** once. Copy it (spaces do not matter).
4. The values you will use:

| Setting | Value |
|---|---|
| Host | `smtp.gmail.com` |
| Port | `587` |
| Username | your full Gmail address |
| Password | the 16-character app password (NOT your normal Gmail password) |
| From | the same Gmail address |

A free Gmail account can send a few hundred emails a day, which is plenty here.

### Option B: Brevo (free plan, good if you do not want to use your own Gmail)
1. Create an account at **brevo.com** and confirm your email.
2. Verify a **sender**: in **Senders, Domains & Dedicated IPs → Senders**, add the email address you want mail to come from and confirm the link Brevo emails to it.
3. Open **SMTP & API → SMTP** and click **Generate a new SMTP key.** Copy the key once.
4. The values you will use:

| Setting | Value |
|---|---|
| Host | `smtp-relay.brevo.com` |
| Port | `587` |
| Username | the "SMTP login" shown on that page |
| Password | the SMTP key you generated |
| From | the verified sender address |

### Put the email values into RideX
1. In `infrastructure\docker-compose\.env` add (Gmail shown; change host/username/from for Brevo):

```
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=you@gmail.com
MAIL_AUTH=true
MAIL_STARTTLS=true
MAIL_FROM=you@gmail.com
```

2. Write the password into its file (your own PowerShell):

```powershell
[IO.File]::WriteAllText("F:\RideX\ridex\infrastructure\docker-compose\secrets\MAIL_PASSWORD", "PASTE-THE-PASSWORD-HERE", (New-Object System.Text.UTF8Encoding($false)))
```

3. Recreate the two services that send mail:

```powershell
cd F:\RideX\ridex\infrastructure\docker-compose
docker compose up -d --force-recreate core notification
```

4. **Test it:** register a new rider with an email address you can read. The verification email should arrive in that **real inbox** (check spam the first time); it no longer appears in Mailpit (http://localhost:8025). To go back to Mailpit, remove the `MAIL_*` lines and recreate the services.

**If nothing arrives:** run `docker logs ridex-core 2>&1 | Select-String -Pattern "mail" -CaseSensitive:$false`. The usual causes: a normal Gmail password instead of an app password; 2-Step Verification is off; the `From` address is not the account's (Gmail) or not a verified sender (Brevo); a typo in the username.

Email problems never break the app: a failed email is logged (without the address or content) and the in-app notification is still there.

## 3. Without Docker Compose
Set the same names as environment variables before starting the services: `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_AUTH`, `MAIL_STARTTLS`, `MAIL_FROM`, `FRONTEND_URL`.

## 4. Razorpay instead of Stripe (alternative)
Razorpay (free test mode, India-focused) is still supported: set `RAZORPAY_KEY_ID` in `.env` and put the key secret in `secrets\RAZORPAY_KEY_SECRET` (and the webhook secret in `secrets\RAZORPAY_WEBHOOK_SECRET`). Stripe wins if both are configured. Razorpay opens its payment window inside RideX instead of a separate page.

## 5. Kubernetes
Put the non-secret values (`SPRING_MAIL_HOST`, `MAIL_FROM`, `STRIPE_PUBLISHABLE_KEY`, ...) in the overlay's ConfigMap and the secrets in an **optional** Secret that the pods mount if it exists:

```bash
kubectl -n ridex create secret generic ridex-integrations \
  --from-literal=MAIL_PASSWORD='...' \
  --from-literal=STRIPE_SECRET_KEY='...' \
  --from-literal=STRIPE_WEBHOOK_SECRET='...'
```

If the secret does not exist the pods still start and use the test checkout and the default mail server.

## 6. What can go wrong

| Symptom | Cause |
|---|---|
| Wallet top-up shows "Test checkout" | no payment keys are configured (the provider is `MOCK`); check the payment log |
| After paying at Stripe you return but the wallet did not change | the secret key does not belong to the same Stripe account/mode as the page you paid on, or `FRONTEND_URL` points somewhere else; check `docker logs ridex-payment` |
| Stripe page says the amount is too small | Stripe's per-currency minimum; try a larger amount |
| You paid, closed the tab before returning, and the wallet is unchanged | no webhook is configured; set it up (section 1) and the wallet is credited once |
| No emails arrive | wrong `MAIL_*`, or Gmail without an app password; see section 2 |
| Emails arrive in spam | free providers; use your own domain with SPF/DKIM when you move beyond a demo |
