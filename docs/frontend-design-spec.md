# RideX frontend design specification (v0.1, for approval)

Status: PLAN ONLY. No frontend code has been written for this redesign yet.
Inputs: 5 reference images (admin dashboard, driver dashboard, rider home x2, landing page)
plus the current repo (backend through Phase 2 + roadmap step 3.1, React auth screens from 2.10).

---------------------------------------------------------------------------------------------------
## 1. What the references show

### Shared app shell (admin, driver, rider)
- Near-black canvas, slightly lighter surfaces, 1px hairline borders, 12-16px radii.
- One accent: warm gold. Used for the active nav item, primary buttons, chart strokes, key numbers,
  progress rings, map route line and the logo chip. Green appears only for positive deltas/online.
- Left sidebar: logo, grouped navigation (small uppercase group label), "Need help?" card, Sign out.
- Top bar: greeting + one-line subtitle, search, notifications (dot), user chip (avatar, name, role).
- Tiny uppercase "eyebrow" labels with wide tracking above section titles.
- Icons sit in small rounded-square chips with a gold-tinted background.
- Content: KPI row (icon chip, big number, caption, delta), a large chart, a donut, lists.

### Role-specific patterns
| Role | Defining pattern |
|---|---|
| Rider | Full-bleed map. A floating "Where to?" booking card (pickup, destination, saved places) sits over it. Nearby-drivers chip. Recent rides + promo card below. |
| Driver | KPI row (earnings, rides, rating, time online). Map with a "next turn" card. Online/offline toggle. "Today's progress" ring. |
| Admin | Dense KPI row, revenue area chart with range switch, category donut, live operations map, pending driver approvals list. |

### Landing page
- Cinematic night photography, very large headline, one-line value proposition.
- Booking inputs directly in the hero (pickup, destination, "See prices").
- Stats row (rides, riders, drivers, cities) and a short brand line bottom-right.
- Top nav: Ride, Drive, Business, About, search, language, Log in, white "Sign up" button.
- The primary CTA is white here, not gold.

### How landing differs from the dashboards
Landing is marketing: photographic, large type, one idea per viewport, generous space, white CTA.
Dashboards are tools: dense, quiet, tokenised surfaces, gold accent, no photography. They must share
tokens (colour, type, radius, motion) so the product feels like one thing.

---------------------------------------------------------------------------------------------------
## 2. Keep / improve / drop

| Keep | Improve | Do not carry over |
|---|---|---|
| Dark canvas + single gold accent | Text as small as ~9px fails accessibility. Minimum 12px, muted text >= 4.5:1 | The Rider / Driver / Admin switcher (a demo device). A real user sees only their own role; the backend decides it |
| Hairline borders, 12-16px radii | Active-nav gold gradient is subtle. Add a 3px edge marker so it is not colour-only | "Simulate ride request" and similar demo buttons |
| Floating booking card over a map | Everything needs real hover/focus/pressed/disabled/loading states | Dollar amounts. RideX uses INR (confirm) |
| KPI row, eyebrow labels, icon chips | Responsive behaviour: sidebar -> rail -> drawer + bottom tabs | Invented numbers shown as live data |
| Cinematic landing hero, stats row | Hero needs real mobile layout and a no-photo fallback | "Super Admin" style role labels not in our model |

---------------------------------------------------------------------------------------------------
## 3. Design system specification

All values live as CSS custom properties in `src/styles/tokens.css` and are exposed to Tailwind.
No hard-coded colours, sizes or durations in components.

### 3.1 Colour (dark only; values are starting points to tune against the images)
| Token | Value | Use |
|---|---|---|
| `--bg-base` | #08080A | page canvas |
| `--bg-surface` | #101012 | cards, sidebar |
| `--bg-elevated` | #17171A | popovers, raised controls |
| `--bg-overlay` | rgba(8,8,10,.72) | scrims, glass panels over maps |
| `--border-subtle` | rgba(255,255,255,.07) | default hairline |
| `--border-strong` | rgba(255,255,255,.14) | inputs, hover |
| `--text-primary` | #F5F5F2 | headings, values |
| `--text-secondary` | #B4B4AE | body |
| `--text-muted` | #8A8A85 | captions (>= 4.5:1 on surface) |
| `--accent` | #E8C547 | primary actions, active, highlights |
| `--accent-hover` / `--accent-pressed` | #F0D369 / #D4B13A | states |
| `--accent-subtle` | rgba(232,197,71,.12) | icon chips, active nav fill |
| `--on-accent` | #17140A | text on gold |
| `--success` `--warning` `--danger` `--info` | #34D17B `#F5A623` `#FF6B6B` `#6AA8FF` | semantic only |

Rule: colour is never the only signal (badges carry text/icon, active nav has an edge marker).

### 3.2 Typography
Font: DM Sans variable, self-hosted (no third-party CDN). One family; weight and size carry hierarchy.
| Style | Size / line height | Weight |
|---|---|---|
| Display (landing hero) | clamp(40px, 6vw, 72px) / 1.05 | 700 |
| H1 | 32 / 40 | 700 |
| H2 | 24 / 32 | 600 |
| H3 | 20 / 28 | 600 |
| Body | 16 / 24 | 400 |
| Body small | 14 / 20 | 400-500 |
| Caption | 12 / 16 | 500 |
| Eyebrow | 12 / 16, uppercase, tracking .12em | 600 |
Numbers in KPIs/tables use tabular figures.

### 3.3 Spacing, radius, elevation
- Spacing: 4px base: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 96.
- Radius: 8 (inputs, chips) / 12 (cards) / 16 (panels) / 24 (hero panels) / full (pills, avatars).
- Elevation (4 levels): 0 canvas; 1 surface (border only, no shadow); 2 raised (border + soft shadow:
  popovers, dropdowns, floating booking card); 3 modal (shadow + scrim).

### 3.4 Neumorphism rules (restrained)
- Allowed ONLY on small tactile controls: toggles (online/offline), segmented controls, icon buttons,
  vehicle-category selector. Technique: a light top-left and dark bottom-right shadow pair of low
  strength, inset when pressed or selected.
- Never on text containers, tables, forms or charts. Never the only affordance: every control keeps a
  border and a visible label/icon.

### 3.5 Glass and spatial depth
- Backdrop blur (12-20px) only for: floating panels over the map, the landing nav on scroll, and
  mobile bottom sheets over content. Always with a solid-enough fill so text stays >= 4.5:1.
- Depth comes from layering order (canvas < surface < raised < overlay), not from glows.
  No glowing borders, no decorative gradients except the single subtle gold wash behind hero stats.

### 3.6 Motion (Motion for React, `motion/react`)
| Token | Value |
|---|---|
| `--dur-fast` / `--dur-base` / `--dur-slow` / `--dur-page` | 120 / 200 / 320 / 480 ms |
| `--ease-standard` | cubic-bezier(.2, 0, 0, 1) |
| `--ease-emphasized` | cubic-bezier(.16, 1, .3, 1) |
| `--ease-exit` | cubic-bezier(.4, 0, 1, 1) |
- Route change: opacity + 8px translate. Dialog/popover: scale .98 + fade. Drawer: slide.
- Ride-status change: step indicator animates, status text cross-fades.
- Lists reveal with a small stagger only on first render.
- `prefers-reduced-motion`: transforms removed, opacity-only, durations halved.
- Animate transform/opacity only (compositor friendly).

### 3.7 Responsive rules
| Width | Behaviour |
|---|---|
| >= 1280 | Full sidebar (labels), multi-column dashboards |
| 1024-1279 | Icon rail sidebar (tooltips), 2-column |
| 768-1023 | Sidebar becomes a drawer, tables keep key columns |
| < 768 | Bottom tab bar (4-5 primary destinations), drawer for the rest, tables become stacked cards, booking becomes a bottom sheet over the map |
Touch targets >= 44px. Landing hero stacks: headline, booking card, stats.

### 3.8 Accessibility baseline
Semantic landmarks, visible focus ring (2px gold + offset) on every interactive element, keyboard
operable dialogs/menus (Radix), form labels + `aria-describedby` errors (already in `FormField`),
live regions for toasts and ride status, contrast checked against the tokens above.

### 3.9 Component inventory
Primitives (shadcn/ui, restyled): Button (primary, secondary, ghost, danger, inverse-white for landing),
Input, Select, Checkbox, Switch, Tabs, Badge, Card, Dialog, Sheet/Drawer, Dropdown menu, Tooltip,
Toast (Sonner), Skeleton, Table, Pagination, Avatar, Progress.
RideX components: Logo, StatCard, StatusBadge (ride/driver/payment statuses), PageHeader, EmptyState,
DataTable (search, filters, pagination), ConfirmDialog, RideStatusStepper, FareBreakdown,
VehicleOption, MapPanel (glass overlay), CountdownRing, ChartCard.

---------------------------------------------------------------------------------------------------
## 4. Screen and route plan

"Backend" says what exists today. Anything not ready is built against clearly labelled preview data
or shown as an honest "coming with <step>" empty state, never as a working control.

### 4.1 Public and auth
| Route | Screen | Backend today |
|---|---|---|
| `/` | Landing (hero + booking teaser, how it works, riders, drivers, safety, features, footer) | none needed; "See prices" routes to `/register` until fare estimate exists (3.6) |
| `/login` | Login | `POST /api/auth/login` (done) |
| `/register` | Rider sign-up | `POST /api/auth/register` role RIDER (done) |
| `/register/driver` | Driver sign-up (account only; licence/vehicle onboarding happens after login, see note) | register role DRIVER (done) |
| `/verify-email` | Email verification (link flow) | `POST /api/auth/verify-email` (done) |
| `/forgot-password`, `/reset-password` | Reset flow | done |
Notes: (1) The backend verifies by emailed link, not OTP. OTP arrives in Phase 4.2. (2) A multi-step
driver registration needs endpoints that require a logged-in account (licence 3.2/3.3, vehicle 3.4), so
"multi-step" becomes a post-login onboarding checklist, not a longer sign-up form.
(3) No admin registration anywhere; admin accounts only come from the seeder.

### 4.2 Rider (`/rider/**`, role RIDER)
| Route | Screen | Needs backend step |
|---|---|---|
| `/rider` | Home: map + "Where to?" card, recent rides | profile (done), rides 3.5-3.9 |
| `/rider/book` | Pickup/destination, vehicle options + fare, confirm | pricing 3.6, request 3.7 |
| `/rider/ride/:id` | Searching, driver assigned, live tracking, progress, completion | 3.7-3.8; live position Phase 5 |
| `/rider/rides`, `/rider/rides/:id` | History, details | 3.9 |
| `/rider/wallet`, `/rider/offers` | Wallet, coupons | Phase 14 (UI later) |
| `/rider/notifications` | Notifications | 5.5 |
| `/rider/profile`, `/rider/settings` | Account | `GET /api/users/me` (done), edit endpoints TBD (3.2) |

### 4.3 Driver (`/driver/**`, role DRIVER)
| Route | Screen | Needs backend step |
|---|---|---|
| `/driver` | Dashboard, online toggle, progress | availability 3.7/4.4 |
| `/driver/onboarding` | Licence, vehicle, approval status checklist | 3.2-3.4 |
| `/driver/requests` | Incoming requests with countdown, accept/decline | 3.7 (timeout/offers Phase 5.4) |
| `/driver/active` | Navigate to pickup, arrived, start, complete | 3.8 |
| `/driver/history`, `/driver/ratings` | Past rides, ratings | 3.9, 3.10 |
| `/driver/earnings` | Earnings + charts | 14.5 |
| `/driver/documents`, `/driver/vehicles`, `/driver/profile`, `/driver/settings` | Management | 3.3, 3.4 |

### 4.4 Admin (`/admin/**`, role ADMIN)
| Route | Screen | Needs backend step |
|---|---|---|
| `/admin` | Overview KPIs, charts | 3.11 basics; analytics 14.6 |
| `/admin/drivers` | Review queue, approve/reject | 3.3 (parked code exists) |
| `/admin/users` | List, search, suspend | 3.11 |
| `/admin/vehicles`, `/admin/rides` | Monitoring | 3.4, 3.9/3.11 |
| `/admin/payments`, `/admin/coupons`, `/admin/complaints`, `/admin/reports` | Finance, support | 6.5, 14.3, 14.7, 14.6 |
| `/admin/settings` | Settings | later |
Shared: `/forbidden`, `*` not found, session-expired handling (already in 2.10).

---------------------------------------------------------------------------------------------------
## 5. Architecture plan

### 5.1 What exists (keep)
React 19.2, Vite 8, TypeScript 6, react-router-dom 7, axios. Auth machinery from 2.10:
`api/client.ts` (interceptors), `api/session.ts` (single-flight refresh), `auth/*`, route guards.
It works and stays; only the presentation layer is replaced.

### 5.2 What gets added (nothing else)
| Package | Why |
|---|---|
| `tailwindcss` + `@tailwindcss/vite` | Utility styling bound to our tokens (already in the original stack) |
| shadcn/ui (CLI, copies source into the repo) with `class-variance-authority`, `clsx`, `tailwind-merge`, Radix primitives | Accessible primitives we own and restyle |
| `lucide-react` | Icons |
| `motion` | Selected animations |
| `recharts` | Charts (when the first chart screen is built) |
| `react-hook-form` + `zod` + `@hookform/resolvers` | Forms and validation, mirrors backend rules |
| `sonner` | Toasts |
| `@fontsource-variable/dm-sans` | Self-hosted font |
Decide later, at first need: TanStack Query (lists/caching) and a map library (MapLibre GL vs Mapbox GL).
Versions are checked against the lockfile at install time; no new library is added "just in case".

### 5.3 Folder structure (extends the current tree)
```
frontend/ridex-web/src/
  styles/            tokens.css, globals.css, fonts
  lib/               cn.ts, format.ts (currency/date), motion.ts (variants from tokens)
  components/
    ui/              shadcn primitives, restyled to tokens
    brand/           Logo, StatCard, StatusBadge, PageHeader, EmptyState, ConfirmDialog ...
  layouts/           PublicLayout, AuthLayout, AppShell (Sidebar, Topbar, MobileNav)
  routes/            router.tsx, navigation.ts (role -> nav items), guards (from auth/)
  api/               client.ts, session.ts, types.ts, errors.ts   (existing)
  auth/              AuthProvider, useAuth, tokenStorage          (existing)
  features/
    landing/         sections, hero booking teaser
    auth/            Login, Register(rider|driver), Verify, Forgot, Reset  (move from pages/)
    rider/           home, booking, ride, history ...
    driver/          dashboard, onboarding, requests, active ...
    admin/           overview, drivers, users ...
    (each feature: pages/, components/, api.ts, mock.ts only when backend is pending)
```
### 5.4 Component hierarchy
`App -> AuthProvider -> Router -> (PublicLayout | AuthLayout | AppShell[role])  -> feature pages -> ui + brand components`
Role navigation comes from one config (`navigation.ts`), so the sidebar, mobile tabs and route guards
cannot drift apart. The backend remains the authority; guards only avoid dead-end screens.

### 5.5 Mock data policy
`features/*/mock.ts` is the only place mock data may live. Screens using it show a visible
"Preview data" badge, and each mock file lists the backend step that replaces it.

---------------------------------------------------------------------------------------------------
## 6. Delivery order

| Stage | Content | Backend dependency |
|---|---|---|
| F0 | Tokens, Tailwind + shadcn setup, primitives, AppShell, motion tokens | none |
| F1 | Landing page | none |
| F2 | Auth screens restyled onto the system (replaces the plain CSS from 2.10) | done |
| F3 | Role shells + real profile pages from `/api/users/me` | done |
| then | Each Phase 3 backend step ships its screens together with its API (3.3 admin approvals, 3.4 vehicles, 3.5-3.8 ride flow, 3.9 history, 3.10 ratings, 3.11 admin) | per step |
| later | Live map/WebSocket (Phase 4-5), wallet/coupons/analytics (Phase 14) | per phase |
Each stage ends with: `npm run build`, `npm run lint`, a responsive check at 375 / 768 / 1280 px, a
keyboard-only pass, and a reduced-motion pass.

---------------------------------------------------------------------------------------------------
## 7. Decisions needed from you
1. **designer.png**: only one landing-page image arrived (the night-street car photo). Is that the
   primary reference, or is `designer.png` a different file?
2. **Hero photo**: may we use that photo as an asset in the repo (rights/consent), or should the hero be
   a built-in illustration (CSS/SVG night city + map lines)? Recommendation: illustration first, photo later.
3. **Accent**: gold as in the references, with white as an inverse button on the landing hero.
4. **Currency**: INR (₹) instead of $.
5. **Role switcher**: removed in the real app (a user only sees their own role).
6. **Maps** (needed at 3.7/3.12): MapLibre GL + free tiles (no key) or Mapbox (token needed).
7. **Order**: F0-F2 now, then 2.11, then 3.2? Or 2.11 first.
