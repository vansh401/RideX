import {
  ArrowRight,
  BadgeCheck,
  Bike,
  Car,
  CarTaxiFront,
  CircleDot,
  Gift,
  MapPin,
  MapPinned,
  MessageCircle,
  ReceiptText,
  Route,
  Share2,
  ShieldAlert,
  ShieldCheck,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import { motion } from 'motion/react'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { reveal, stagger } from '@/lib/motion'
import { homePathFor } from '@/routes/navigation'
import { HeroArt } from './HeroArt'
import { HeroWindows } from './HeroWindows'

/** Fades a section's content in as it scrolls into view (once). */
function Reveal({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div className={className} variants={reveal} initial="hidden" whileInView="visible" viewport={{ once: true, margin: '-80px' }}>
      {children}
    </motion.div>
  )
}

/** Children appear one after another as the group scrolls into view. */
function Stagger({ children, className, as = 'ul' }: { children: ReactNode; className?: string; as?: 'ul' | 'ol' | 'div' }) {
  const Tag = motion[as]
  return (
    <Tag variants={stagger} initial="hidden" whileInView="visible" viewport={{ once: true, margin: '-80px' }} className={className}>
      {children}
    </Tag>
  )
}

function SectionHeading({ eyebrow, title, text, center = false }: { eyebrow: string; title: ReactNode; text?: string; center?: boolean }) {
  return (
    <div className={center ? 'mx-auto max-w-2xl text-center' : 'max-w-2xl'}>
      <p className="eyebrow mb-4 flex items-center gap-2 text-accent" style={center ? { justifyContent: 'center' } : undefined}>
        <span aria-hidden className="size-1.5 rounded-full bg-accent" /> {eyebrow}
      </p>
      <h2 className="display text-[clamp(2rem,4.6vw,3.4rem)] text-fg">{title}</h2>
      {text && <p className="mt-5 text-lg leading-relaxed text-fg-secondary">{text}</p>}
    </div>
  )
}

// ------------------------------------------------------------------------------------------------ hero

function Hero() {
  const { status, user } = useAuth()
  const navigate = useNavigate()
  const [pickup, setPickup] = useState('')
  const [destination, setDestination] = useState('')
  const signedIn = status === 'authenticated' && user

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    navigate(signedIn ? homePathFor(user.role) : '/register')
  }

  return (
    <section className="relative isolate overflow-hidden pt-16">
      {/* The scene on the right, fading into the page on the left so the words stay readable */}
      <HeroArt className="absolute inset-y-0 right-0 -z-10 h-full w-full opacity-90 md:w-[78%]" />
      <div className="absolute inset-0 -z-10 bg-gradient-to-r from-canvas via-canvas/70 to-transparent" />
      <div className="absolute inset-x-0 bottom-0 -z-10 h-48 bg-gradient-to-t from-canvas to-transparent" />
      <HeroWindows />

      <div className="mx-auto flex min-h-[calc(100svh-4rem)] max-w-7xl flex-col justify-center gap-10 px-4 py-20 sm:px-6">
        <motion.div variants={stagger} initial="hidden" animate="visible" className="max-w-2xl">
          <motion.p variants={reveal} className="eyebrow mb-6 flex items-center gap-2 text-accent">
            <span aria-hidden className="size-1.5 rounded-full bg-accent" /> A smarter way to move
          </motion.p>
          <motion.h1 variants={reveal} className="display text-[clamp(3rem,7.4vw,6rem)] text-fg">
            Move through the city <em>effortlessly.</em>
          </motion.h1>
          <motion.p variants={reveal} className="mt-6 max-w-xl text-xl leading-relaxed text-fg-secondary">
            Book in seconds, follow your driver live, and pay the way you like. Calm, clear and in your control from pickup to drop-off.
          </motion.p>

          <motion.form
            variants={reveal}
            onSubmit={handleSubmit}
            aria-label="Plan a trip"
            className="glass glass-float mt-10 flex max-w-md flex-col gap-3 rounded-xl p-4"
          >
            <div className="relative">
              <MapPin className="absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-fg-muted" aria-hidden />
              <Input aria-label="Pickup location" placeholder="Enter pickup location" className="pl-11" value={pickup} onChange={(e) => setPickup(e.target.value)} />
            </div>
            <div className="relative">
              <CircleDot className="absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-accent" aria-hidden />
              <Input aria-label="Destination" placeholder="Enter destination" className="pl-11" value={destination} onChange={(e) => setDestination(e.target.value)} />
            </div>
            <Button type="submit" variant="inverse" size="lg">
              {signedIn ? 'Open RideX' : 'Sign up to see prices'}
              <ArrowRight className="size-5" aria-hidden />
            </Button>
          </motion.form>

          <motion.ul variants={reveal} className="mt-9 flex flex-wrap gap-x-8 gap-y-3 text-sm text-fg-secondary">
            <li className="flex items-center gap-2"><BadgeCheck className="size-4 text-accent" aria-hidden /> Reviewed drivers</li>
            <li className="flex items-center gap-2"><Wallet className="size-4 text-accent" aria-hidden /> Wallet and offers</li>
            <li className="flex items-center gap-2"><Route className="size-4 text-accent" aria-hidden /> Live trip status</li>
          </motion.ul>
        </motion.div>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ ticker

const TICKER = ['Live trip tracking', 'Wallet and offers', 'Reviewed drivers', 'SOS and trip sharing', 'In-ride chat', 'Receipts for every ride', 'Invite and earn']

/** A slow band of what RideX does. It is decoration: assistive technology skips it, and it stops under reduced motion. */
function Ticker() {
  const row = [...TICKER, ...TICKER]
  return (
    <div aria-hidden className="relative overflow-hidden border-y border-border bg-surface/50 py-5 [mask-image:linear-gradient(90deg,transparent,#000_8%,#000_92%,transparent)]">
      <div className="flex w-max animate-marquee gap-12 whitespace-nowrap">
        {row.map((item, i) => (
          <span key={i} className="display flex items-center gap-12 text-2xl italic text-fg-secondary">
            {item}
            <span className="size-1.5 rounded-full bg-accent" />
          </span>
        ))}
      </div>
    </div>
  )
}

// ------------------------------------------------------------------------------------------------ vehicles

const FLEET: { icon: LucideIcon; name: string; seats: string; text: string }[] = [
  { icon: Bike, name: 'Bike', seats: '1 rider', text: 'The quickest way through traffic for a solo trip.' },
  { icon: CarTaxiFront, name: 'Auto', seats: 'Up to 3 riders', text: 'An easy short hop across town for you and a friend or two.' },
  { icon: Car, name: 'Cab', seats: 'Up to 6 riders', text: 'Room and comfort for longer trips and for groups.' },
]

function Fleet() {
  return (
    <section id="rides" className="scroll-mt-20 py-28">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Reveal>
          <SectionHeading eyebrow="Your ride, your way" title={<>Three ways to <em>arrive.</em></>} text="See each fare in rupees before you confirm, then choose the one that suits the moment." />
        </Reveal>
        <Stagger className="mt-14 grid gap-6 md:grid-cols-3">
          {FLEET.map(({ icon: Icon, name, seats, text }) => (
            <motion.li key={name} variants={reveal} className="panel lift group relative overflow-hidden rounded-xl border border-border p-8">
              <span aria-hidden className="absolute -right-10 -top-10 size-40 rounded-full bg-accent/10 blur-2xl transition-opacity duration-(--dur-slow) group-hover:opacity-100" />
              <span className="neu relative grid size-16 place-items-center rounded-full text-accent">
                <Icon className="size-7" aria-hidden />
              </span>
              <h3 className="display relative mt-8 text-4xl text-fg">{name}</h3>
              <p className="eyebrow relative mt-2">{seats}</p>
              <p className="relative mt-5 text-fg-secondary">{text}</p>
            </motion.li>
          ))}
        </Stagger>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ steps

const STEPS = [
  { title: 'Set your trip', text: 'Choose where you are and where you are going. Add a stop, or book it for later.' },
  { title: 'Meet your driver', text: 'A nearby, reviewed driver accepts. Follow them to your pickup, message them, and share the trip with someone you trust.' },
  { title: 'Ride, pay, rate', text: 'Pay from your wallet or by card, keep the receipt, tip if you like, and rate the trip.' },
]

function HowItWorks() {
  return (
    <section id="how" className="scroll-mt-20 py-28">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Reveal>
          <SectionHeading eyebrow="How it works" title={<>From request to drop-off in <em>three steps.</em></>} />
        </Reveal>
        <Stagger as="ol" className="mt-16 grid gap-x-10 gap-y-14 md:grid-cols-3">
          {STEPS.map(({ title, text }, index) => (
            <motion.li key={title} variants={reveal} className="relative border-t border-border-strong pt-8">
              <span aria-hidden className="display absolute right-0 top-2 select-none text-[6rem] leading-none text-accent/20">
                {index + 1}
              </span>
              <p className="eyebrow text-accent">Step 0{index + 1}</p>
              <h3 className="display mt-4 text-3xl text-fg">{title}</h3>
              <p className="mt-4 max-w-sm text-fg-secondary">{text}</p>
            </motion.li>
          ))}
        </Stagger>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ features bento

interface Tile {
  icon: LucideIcon
  title: string
  text: string
  span?: string
  visual?: ReactNode
}

const TILES: Tile[] = [
  {
    icon: MapPinned,
    title: 'Follow every kilometre',
    text: 'Your driver moves on a live map from the moment they accept until you arrive, with clear status at each step.',
    span: 'md:col-span-2',
    visual: (
      <svg aria-hidden viewBox="0 0 320 90" className="mt-6 h-24 w-full" fill="none">
        <path d="M6 70 C 70 70, 90 20, 150 38 S 250 70, 314 18" stroke="var(--border-strong)" strokeWidth="8" strokeLinecap="round" />
        <path d="M6 70 C 70 70, 90 20, 150 38 S 250 70, 314 18" stroke="var(--accent)" strokeWidth="3" strokeLinecap="round" strokeDasharray="2 9" style={{ animation: 'route-flow 1.4s linear infinite' }} />
        <circle cx="6" cy="70" r="7" fill="var(--text-primary)" />
        <circle cx="314" cy="18" r="8" fill="var(--accent)" />
      </svg>
    ),
  },
  { icon: Wallet, title: 'A wallet for rides', text: 'Top up once and pay in a tap. Offers and invite rewards land here too.' },
  { icon: ShieldAlert, title: 'SOS, one press away', text: 'Alert our safety team and your emergency contact from inside any trip.' },
  { icon: Share2, title: 'Share your trip', text: 'Send a private link so a friend can follow your ride. It expires on its own.' },
  { icon: MessageCircle, title: 'Talk to your driver', text: 'Tell them which gate you are at, right inside the ride.', span: 'md:col-span-1' },
  { icon: ReceiptText, title: 'Receipts, always', text: 'A clean receipt for every paid ride, ready to print or save.', span: 'md:col-span-2' },
  { icon: Gift, title: 'Offers and invites', text: 'Browse offers you can use, and earn wallet credit when a friend takes their first ride.' },
]

function Features() {
  return (
    <section id="features" className="scroll-mt-20 py-28">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Reveal>
          <SectionHeading eyebrow="Everything in one place" title={<>Thoughtful in the <em>details.</em></>} text="The small things that make a ride feel looked after." />
        </Reveal>
        <Stagger className="mt-14 grid gap-5 md:grid-cols-3">
          {TILES.map(({ icon: Icon, title, text, span, visual }) => (
            <motion.li key={title} variants={reveal} className={`panel lift flex flex-col rounded-xl border border-border p-7 ${span ?? ''}`}>
              <span className="grid size-11 place-items-center rounded-full bg-accent-subtle text-accent ring-1 ring-accent/20">
                <Icon className="size-5" aria-hidden />
              </span>
              <h3 className="display mt-6 text-2xl text-fg">{title}</h3>
              <p className="mt-2 text-fg-secondary">{text}</p>
              {visual}
            </motion.li>
          ))}
        </Stagger>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ safety (an inverted band)

const SAFETY: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: ShieldCheck, title: 'Drivers are reviewed', text: 'Licence details are checked and approved by an administrator before a driver can take a ride.' },
  { icon: BadgeCheck, title: 'Verified accounts', text: 'Email addresses are confirmed before an account is activated, and every sign-in can be ended remotely.' },
  { icon: ShieldAlert, title: 'Help when it matters', text: 'The SOS button alerts administrators and your emergency contact with a live link to your trip.' },
]

function Safety() {
  return (
    <section id="safety" className="scroll-mt-20 px-4 py-10 sm:px-6">
      <div className="relative mx-auto max-w-7xl overflow-hidden rounded-xl bg-fg px-6 py-20 text-canvas sm:px-12">
        <span aria-hidden className="absolute -right-24 -top-24 size-96 rounded-full bg-accent/25 blur-3xl" />
        <Reveal className="relative max-w-2xl">
          <p className="eyebrow mb-4 flex items-center gap-2 !text-canvas/70">
            <span aria-hidden className="size-1.5 rounded-full bg-accent" /> Safety
          </p>
          <h2 className="display text-[clamp(2rem,4.6vw,3.4rem)] text-canvas">
            Built to be <em className="!text-canvas">trusted.</em>
          </h2>
          <p className="mt-5 text-lg text-canvas/75">Care that is part of the ride, not an extra.</p>
        </Reveal>
        <Stagger className="relative mt-14 grid gap-6 md:grid-cols-3">
          {SAFETY.map(({ icon: Icon, title, text }) => (
            <motion.li key={title} variants={reveal} className="rounded-lg border border-canvas/15 bg-canvas/10 p-7">
              <Icon className="size-6 text-accent" aria-hidden />
              <h3 className="display mt-5 text-2xl text-canvas">{title}</h3>
              <p className="mt-2 text-canvas/70">{text}</p>
            </motion.li>
          ))}
        </Stagger>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ drivers

const DRIVER_STEPS = ['Create your driver account and verify your email', 'Add your licence details and vehicle', 'Wait for a quick review by the RideX team', 'Go online and start accepting rides']

function Drivers() {
  return (
    <section id="drivers" className="scroll-mt-20 py-28">
      <div className="mx-auto grid max-w-7xl gap-14 px-4 sm:px-6 lg:grid-cols-2 lg:items-center">
        <Reveal>
          <SectionHeading
            eyebrow="For drivers"
            title={<>Drive on your <em>own schedule.</em></>}
            text="Choose when you are online, see each request before you accept it, track your earnings and withdraw them to your UPI."
          />
          <div className="mt-9">
            <Button asChild size="lg">
              <Link to="/register/driver">
                Drive with RideX <ArrowRight className="size-5" aria-hidden />
              </Link>
            </Button>
          </div>
        </Reveal>
        <Reveal>
          <ol className="space-y-3">
            {DRIVER_STEPS.map((step, index) => (
              <li key={step} className="panel flex items-center gap-5 rounded-lg border border-border p-5">
                <span className="display grid size-12 shrink-0 place-items-center rounded-full bg-accent-subtle text-2xl text-accent">{index + 1}</span>
                <span className="text-fg">{step}</span>
              </li>
            ))}
          </ol>
        </Reveal>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------------------------------------ closing

function ClosingCta() {
  return (
    <section className="px-4 pb-28 pt-6 sm:px-6">
      <Reveal className="panel relative mx-auto max-w-5xl overflow-hidden rounded-xl border border-border px-6 py-20 text-center">
        <span aria-hidden className="absolute left-1/2 top-0 size-80 -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent/20 blur-3xl" />
        <h2 className="display relative text-[clamp(2.4rem,6vw,4.6rem)] text-fg">
          Ready when <em>you are.</em>
        </h2>
        <p className="relative mx-auto mt-5 max-w-md text-lg text-fg-secondary">Create your account in a minute and take your first ride today.</p>
        <div className="relative mt-9 flex flex-wrap justify-center gap-3">
          <Button asChild variant="inverse" size="lg">
            <Link to="/register">Sign up</Link>
          </Button>
          <Button asChild variant="secondary" size="lg">
            <Link to="/login">Log in</Link>
          </Button>
        </div>
      </Reveal>
    </section>
  )
}

export function LandingPage() {
  return (
    <main>
      <Hero />
      <Ticker />
      <Fleet />
      <HowItWorks />
      <Features />
      <Safety />
      <Drivers />
      <ClosingCta />
    </main>
  )
}
