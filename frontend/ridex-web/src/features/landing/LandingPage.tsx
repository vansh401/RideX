import {
  ArrowRight,
  BadgeCheck,
  CalendarClock,
  CircleDot,
  Clock3,
  IndianRupee,
  LockKeyhole,
  MapPin,
  MapPinned,
  Route,
  ShieldCheck,
  Smartphone,
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

/** Fades a section's content in as it scrolls into view (once). */
function Reveal({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <motion.div
      className={className}
      variants={reveal}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, margin: '-80px' }}
    >
      {children}
    </motion.div>
  )
}

function SectionHeading({ eyebrow, title, text }: { eyebrow: string; title: string; text?: string }) {
  return (
    <div className="max-w-2xl">
      <p className="eyebrow mb-3 text-accent">{eyebrow}</p>
      <h2 className="text-3xl font-bold leading-tight tracking-tight text-fg sm:text-4xl">{title}</h2>
      {text && <p className="mt-4 text-lg text-fg-secondary">{text}</p>}
    </div>
  )
}

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
      {/* Illustration on the right, fading into the page on the left so text stays readable */}
      <HeroArt className="absolute inset-y-0 right-0 -z-10 h-full w-full opacity-80 md:w-[75%]" />
      <div className="absolute inset-0 -z-10 bg-gradient-to-r from-canvas via-canvas/85 to-transparent" />
      <div className="absolute inset-x-0 bottom-0 -z-10 h-40 bg-gradient-to-t from-canvas to-transparent" />

      <div className="mx-auto flex min-h-[calc(100svh-4rem)] max-w-7xl flex-col justify-center gap-10 px-4 py-16 sm:px-6">
        <motion.div variants={stagger} initial="hidden" animate="visible" className="max-w-2xl">
          <motion.p variants={reveal} className="eyebrow mb-5">
            A smarter way to move
          </motion.p>
          <motion.h1
            variants={reveal}
            className="text-[clamp(2.5rem,6vw,4.5rem)] font-bold leading-[1.05] tracking-tight text-fg"
          >
            Go anywhere
            <br />
            with RideX
          </motion.h1>
          <motion.p variants={reveal} className="mt-5 max-w-xl text-xl text-fg-secondary">
            Request a ride, hop in, and go.
          </motion.p>

          <motion.form
            variants={reveal}
            onSubmit={handleSubmit}
            aria-label="Plan a trip"
            className="glass mt-8 flex max-w-md flex-col gap-3 rounded-xl p-4 shadow-raised"
          >
            <div className="relative">
              <MapPin className="absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-fg-muted" aria-hidden />
              <Input
                aria-label="Pickup location"
                placeholder="Enter pickup location"
                className="pl-11"
                value={pickup}
                onChange={(event) => setPickup(event.target.value)}
              />
            </div>
            <div className="relative">
              <CircleDot className="absolute left-3.5 top-1/2 size-5 -translate-y-1/2 text-accent" aria-hidden />
              <Input
                aria-label="Destination"
                placeholder="Enter destination"
                className="pl-11"
                value={destination}
                onChange={(event) => setDestination(event.target.value)}
              />
            </div>
            <Button type="submit" variant="inverse" size="lg">
              {signedIn ? 'Open RideX' : 'Sign up to see prices'}
              <ArrowRight className="size-5" aria-hidden />
            </Button>
          </motion.form>

          <motion.ul variants={reveal} className="mt-8 flex flex-wrap gap-x-8 gap-y-3 text-sm text-fg-secondary">
            <li className="flex items-center gap-2"><BadgeCheck className="size-4 text-accent" aria-hidden /> Reviewed drivers</li>
            <li className="flex items-center gap-2"><IndianRupee className="size-4 text-accent" aria-hidden /> Fares in rupees</li>
            <li className="flex items-center gap-2"><Route className="size-4 text-accent" aria-hidden /> Live trip status</li>
          </motion.ul>
        </motion.div>
      </div>
    </section>
  )
}

const STEPS: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: MapPinned, title: 'Set your trip', text: 'Choose where you are and where you are going, then pick a vehicle that suits you.' },
  { icon: Clock3, title: 'Get matched', text: 'A nearby approved driver accepts your request, and you can follow them to your pickup.' },
  { icon: Smartphone, title: 'Ride and rate', text: 'Enjoy the trip, pay, and rate your driver. Your trip history is always one tap away.' },
]

function HowItWorks() {
  return (
    <section id="how" className="scroll-mt-20 border-t border-border py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Reveal>
          <SectionHeading eyebrow="How it works" title="From request to drop-off in three steps" />
        </Reveal>
        <motion.ol
          variants={stagger}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: '-80px' }}
          className="mt-12 grid gap-6 md:grid-cols-3"
        >
          {STEPS.map(({ icon: Icon, title, text }, index) => (
            <motion.li key={title} variants={reveal} className="rounded-lg border border-border bg-surface p-6">
              <div className="flex items-center justify-between">
                <span className="grid size-11 place-items-center rounded-md bg-accent-subtle text-accent">
                  <Icon className="size-5" aria-hidden />
                </span>
                <span className="tabular text-sm font-semibold text-fg-muted">0{index + 1}</span>
              </div>
              <h3 className="mt-5 text-xl font-semibold text-fg">{title}</h3>
              <p className="mt-2 text-fg-secondary">{text}</p>
            </motion.li>
          ))}
        </motion.ol>
      </div>
    </section>
  )
}

const RIDER_POINTS: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: IndianRupee, title: 'Know the fare first', text: 'See the estimated fare in rupees before you confirm.' },
  { icon: MapPinned, title: 'Pick your ride', text: 'Bike, auto or cab, based on how you want to travel.' },
  { icon: Route, title: 'Follow your trip', text: 'Clear status from searching to arrival and drop-off.' },
  { icon: Clock3, title: 'Every trip on record', text: 'Receipts and ride history, whenever you need them.' },
]

function Riders() {
  return (
    <section id="riders" className="scroll-mt-20 border-t border-border bg-surface py-24">
      <div className="mx-auto grid max-w-7xl gap-12 px-4 sm:px-6 lg:grid-cols-[1fr_1.2fr] lg:items-center">
        <Reveal>
          <SectionHeading
            eyebrow="For riders"
            title="Travel with clarity"
            text="Everything you need to decide, ride and review, without the guesswork."
          />
          <div className="mt-8 flex flex-wrap gap-3">
            <Button asChild size="lg">
              <Link to="/register">Sign up to ride</Link>
            </Button>
            <Button asChild variant="secondary" size="lg">
              <Link to="/login">Log in</Link>
            </Button>
          </div>
        </Reveal>
        <motion.ul
          variants={stagger}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: '-80px' }}
          className="grid gap-4 sm:grid-cols-2"
        >
          {RIDER_POINTS.map(({ icon: Icon, title, text }) => (
            <motion.li key={title} variants={reveal} className="rounded-lg border border-border bg-canvas p-5">
              <Icon className="size-6 text-accent" aria-hidden />
              <h3 className="mt-4 font-semibold text-fg">{title}</h3>
              <p className="mt-1 text-sm text-fg-secondary">{text}</p>
            </motion.li>
          ))}
        </motion.ul>
      </div>
    </section>
  )
}

const DRIVER_STEPS = [
  'Create your driver account and verify your email',
  'Add your licence details and vehicle',
  'Wait for a quick review by the RideX team',
  'Go online and start accepting rides',
]

function Drivers() {
  return (
    <section id="drivers" className="scroll-mt-20 border-t border-border py-24">
      <div className="mx-auto grid max-w-7xl gap-12 px-4 sm:px-6 lg:grid-cols-2 lg:items-center">
        <Reveal>
          <SectionHeading
            eyebrow="For drivers"
            title="Drive on your own schedule"
            text="Choose when you are online, see each request before you accept it, and keep track of your trips."
          />
          <div className="mt-8">
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
              <li key={step} className="flex items-center gap-4 rounded-lg border border-border bg-surface p-4">
                <span className="tabular grid size-10 shrink-0 place-items-center rounded-full bg-accent-subtle font-bold text-accent">
                  {index + 1}
                </span>
                <span className="text-fg">{step}</span>
              </li>
            ))}
          </ol>
        </Reveal>
      </div>
    </section>
  )
}

const SAFETY: { icon: LucideIcon; title: string; text: string }[] = [
  { icon: ShieldCheck, title: 'Drivers are reviewed', text: 'Licence details are checked and approved by an admin before a driver can take rides.' },
  { icon: LockKeyhole, title: 'Accounts stay yours', text: 'Passwords are hashed, sessions are short-lived, and every sign-in can be ended remotely.' },
  { icon: BadgeCheck, title: 'Verified contacts', text: 'Email addresses are confirmed before an account is activated.' },
  { icon: CalendarClock, title: 'A record of every trip', text: 'Ride status changes are recorded so there is always a clear history.' },
]

function Safety() {
  return (
    <section id="safety" className="scroll-mt-20 border-t border-border bg-surface py-24">
      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <Reveal>
          <SectionHeading eyebrow="Safety" title="Built to be trusted" />
        </Reveal>
        <motion.ul
          variants={stagger}
          initial="hidden"
          whileInView="visible"
          viewport={{ once: true, margin: '-80px' }}
          className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4"
        >
          {SAFETY.map(({ icon: Icon, title, text }) => (
            <motion.li key={title} variants={reveal} className="rounded-lg border border-border bg-canvas p-6">
              <span className="grid size-11 place-items-center rounded-md bg-accent-subtle text-accent">
                <Icon className="size-5" aria-hidden />
              </span>
              <h3 className="mt-5 font-semibold text-fg">{title}</h3>
              <p className="mt-2 text-sm text-fg-secondary">{text}</p>
            </motion.li>
          ))}
        </motion.ul>
      </div>
    </section>
  )
}

function ClosingCta() {
  return (
    <section className="border-t border-border py-24">
      <Reveal className="mx-auto max-w-3xl px-4 text-center sm:px-6">
        <h2 className="text-3xl font-bold tracking-tight text-fg sm:text-5xl">Ready to ride?</h2>
        <p className="mt-4 text-lg text-fg-secondary">Create your account in a minute.</p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
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
      <HowItWorks />
      <Riders />
      <Drivers />
      <Safety />
      <ClosingCta />
    </main>
  )
}
