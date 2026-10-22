import { Bike, Car, CarTaxiFront, MapPin, Phone, MessageCircle } from 'lucide-react'
import { motion, useMotionValue, useReducedMotion, useSpring, useTransform, type MotionValue } from 'motion/react'
import { useEffect, type ReactNode } from 'react'

/**
 * The hero's spatial scene: three glass windows (a live trip, a driver card, a fare picker) floating at
 * different depths. Moving the mouse shifts each one by a different amount (parallax), so they read as
 * layers in space. They illustrate the product and carry no figures, names or ratings, so nothing here
 * can be mistaken for a claim. The scene is decorative (aria-hidden) and only shown from the lg breakpoint.
 */

function Dots() {
  return (
    <span className="flex gap-1.5" aria-hidden>
      <i className="size-2.5 rounded-full bg-[#ff5f57]/80" />
      <i className="size-2.5 rounded-full bg-[#febc2e]/80" />
      <i className="size-2.5 rounded-full bg-[#28c840]/80" />
    </span>
  )
}

function Bar({ w, accent = false }: { w: string; accent?: boolean }) {
  return <span className={`block h-2.5 rounded-full ${accent ? 'bg-accent/60' : 'bg-white/15'}`} style={{ width: w }} />
}

function Floating({
  depth,
  mx,
  my,
  delay,
  className,
  children,
}: {
  depth: number
  mx: MotionValue<number>
  my: MotionValue<number>
  delay: number
  className: string
  children: ReactNode
}) {
  const x = useTransform(mx, [-1, 1], [-depth, depth])
  const y = useTransform(my, [-1, 1], [-depth, depth])
  return (
    <motion.div
      style={{ x, y }}
      initial={{ opacity: 0, scale: 0.92, filter: 'blur(10px)' }}
      animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
      transition={{ duration: 0.9, delay, ease: [0.16, 1, 0.3, 1] }}
      className={`absolute ${className}`}
    >
      <div className="animate-float-slow" style={{ animationDelay: `${delay * -6}s` }}>
        {children}
      </div>
    </motion.div>
  )
}

export function HeroWindows() {
  const reduce = useReducedMotion()
  const rawX = useMotionValue(0)
  const rawY = useMotionValue(0)
  const mx = useSpring(rawX, { stiffness: 80, damping: 18 })
  const my = useSpring(rawY, { stiffness: 80, damping: 18 })

  useEffect(() => {
    if (reduce) return
    function onMove(event: PointerEvent) {
      if (event.pointerType !== 'mouse') return
      rawX.set((event.clientX / window.innerWidth) * 2 - 1)
      rawY.set((event.clientY / window.innerHeight) * 2 - 1)
    }
    window.addEventListener('pointermove', onMove, { passive: true })
    return () => window.removeEventListener('pointermove', onMove)
  }, [reduce, rawX, rawY])

  return (
    <div aria-hidden className="pointer-events-none absolute inset-y-0 right-0 hidden w-[56%] lg:block">
      {/* Trip window: the largest, furthest back */}
      <Floating depth={10} mx={mx} my={my} delay={0.25} className="right-[8%] top-[16%] w-[26rem]">
        <div className="glass glass-float rounded-xl p-4">
          <div className="mb-3 flex items-center justify-between">
            <Dots />
            <span className="flex items-center gap-2 rounded-full bg-success/15 px-2.5 py-1 text-xs font-semibold text-success">
              <span className="size-1.5 animate-pulse rounded-full bg-success" /> Driver on the way
            </span>
          </div>
          <div className="relative h-44 overflow-hidden rounded-lg border border-white/10 bg-black/30">
            <svg viewBox="0 0 400 176" className="absolute inset-0 size-full" fill="none">
              <path d="M0 120 C 90 100, 120 150, 210 110 S 330 60, 400 70" stroke="#ffffff" strokeOpacity=".07" strokeWidth="26" />
              <path d="M70 0 C 90 60, 60 110, 110 176" stroke="#ffffff" strokeOpacity=".06" strokeWidth="22" />
              <path d="M52 140 C 110 100, 160 130, 220 92 S 320 50, 352 34" stroke="#e9c84a" strokeWidth="3" strokeLinecap="round" strokeDasharray="2 9" style={{ animation: 'route-flow 1.4s linear infinite' }} />
              <circle cx="52" cy="140" r="7" fill="#f6f6f3" />
              <circle cx="352" cy="34" r="8" fill="#e9c84a" />
            </svg>
          </div>
          <div className="mt-3 space-y-2.5">
            <div className="flex items-center gap-2.5">
              <MapPin className="size-4 text-fg-muted" />
              <Bar w="58%" />
            </div>
            <div className="flex items-center gap-2.5">
              <MapPin className="size-4 text-accent" />
              <Bar w="72%" accent />
            </div>
          </div>
        </div>
      </Floating>

      {/* Driver card: mid depth, overlapping the trip window */}
      <Floating depth={22} mx={mx} my={my} delay={0.5} className="right-[34%] top-[58%] w-72">
        <div className="glass glass-float rounded-xl p-4">
          <div className="flex items-center gap-3">
            <span className="grid size-12 place-items-center rounded-full bg-gradient-to-br from-accent/35 to-accent/5 ring-1 ring-accent/30">
              <Car className="size-5 text-accent" />
            </span>
            <div className="flex-1 space-y-2">
              <Bar w="55%" />
              <Bar w="38%" />
            </div>
          </div>
          <div className="mt-4 flex gap-2.5">
            <span className="neu grid h-10 flex-1 place-items-center rounded-md text-fg-secondary">
              <Phone className="size-4" />
            </span>
            <span className="neu grid h-10 flex-1 place-items-center rounded-md text-fg-secondary">
              <MessageCircle className="size-4" />
            </span>
          </div>
        </div>
      </Floating>

      {/* Fare picker: nearest to the viewer, moves the most */}
      <Floating depth={36} mx={mx} my={my} delay={0.75} className="right-[2%] top-[62%] w-64">
        <div className="glass glass-float rounded-xl p-3.5">
          <div className="flex gap-1.5 rounded-lg bg-black/30 p-1 shadow-neu-inset">
            {[
              { Icon: Bike, on: false },
              { Icon: CarTaxiFront, on: false },
              { Icon: Car, on: true },
            ].map(({ Icon, on }, i) => (
              <span
                key={i}
                className={`grid h-10 flex-1 place-items-center rounded-md ${on ? 'bg-gradient-to-b from-accent-hover to-accent text-on-accent shadow-glow' : 'text-fg-muted'}`}
              >
                <Icon className="size-5" />
              </span>
            ))}
          </div>
          <div className="mt-3 flex items-center justify-between">
            <Bar w="34%" />
            <Bar w="22%" accent />
          </div>
        </div>
      </Floating>
    </div>
  )
}
