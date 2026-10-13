import { ShieldCheck, Route, Wallet } from 'lucide-react'
import { Link, Outlet } from 'react-router-dom'
import { Logo } from '@/components/brand/Logo'
import { PageTransition } from '@/components/brand/PageTransition'
import { HeroArt } from '@/features/landing/HeroArt'

const POINTS = [
  { icon: ShieldCheck, text: 'Every driver is reviewed before taking a ride' },
  { icon: Wallet, text: 'See your fare in rupees before you book' },
  { icon: Route, text: 'Follow your trip from pickup to drop-off' },
]

/**
 * Split layout for login, sign-up, verification and reset: brand panel on the left (hidden on
 * small screens), the form on the right. Shares its illustration with the landing hero.
 */
export function AuthLayout() {
  return (
    <div className="grid min-h-screen bg-canvas lg:grid-cols-[1.05fr_1fr]">
      <aside className="relative hidden overflow-hidden border-r border-border lg:block">
        <HeroArt className="absolute inset-0 size-full opacity-90" />
        <div className="absolute inset-0 bg-gradient-to-t from-canvas via-canvas/50 to-transparent" />
        <div className="relative flex h-full flex-col justify-between p-10">
          <Link to="/" aria-label="RideX home">
            <Logo />
          </Link>
          <div className="max-w-md">
            <h2 className="text-4xl font-bold leading-tight tracking-tight text-fg">
              A smarter way to move.
            </h2>
            <ul className="mt-6 space-y-3">
              {POINTS.map(({ icon: Icon, text }) => (
                <li key={text} className="flex items-center gap-3 text-fg-secondary">
                  <span className="grid size-9 shrink-0 place-items-center rounded-md bg-accent-subtle text-accent">
                    <Icon className="size-4" aria-hidden />
                  </span>
                  {text}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </aside>

      <main className="flex flex-col px-4 py-6 sm:px-8">
        <div className="lg:hidden">
          <Link to="/" aria-label="RideX home">
            <Logo />
          </Link>
        </div>
        <div className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center py-10">
          <PageTransition>
            <Outlet />
          </PageTransition>
        </div>
      </main>
    </div>
  )
}
