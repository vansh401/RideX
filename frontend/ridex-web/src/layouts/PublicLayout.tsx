import { Menu } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { Logo } from '@/components/brand/Logo'
import { ThemeToggle } from '@/components/brand/ThemeToggle'
import { Button } from '@/components/ui/button'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { cn } from '@/lib/cn'
import { homePathFor } from '@/routes/navigation'

const LINKS = [
  { href: '/#how', label: 'How it works' },
  { href: '/#rides', label: 'Ride' },
  { href: '/#drivers', label: 'Drive' },
  { href: '/#safety', label: 'Safety' },
]

function PublicNav() {
  const { status, user } = useAuth()
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 16)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  const signedIn = status === 'authenticated' && user

  return (
    <header
      className={cn(
        'fixed inset-x-0 top-0 z-40 transition-colors duration-(--dur-base)',
        scrolled ? 'glass border-x-0 border-t-0' : 'border-b border-transparent',
      )}
    >
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
        <Link to="/" aria-label="RideX home">
          <Logo />
        </Link>

        <nav aria-label="Primary" className="hidden items-center gap-8 md:flex">
          {LINKS.map((link) => (
            <a key={link.href} href={link.href} className="text-sm font-medium text-fg-secondary hover:text-fg">
              {link.label}
            </a>
          ))}
        </nav>

        <div className="hidden items-center gap-3 md:flex">
          <ThemeToggle />
          {signedIn ? (
            <Button asChild variant="inverse" size="sm">
              <Link to={homePathFor(user.role)}>Open RideX</Link>
            </Button>
          ) : (
            <>
              <Button asChild variant="ghost" size="sm">
                <Link to="/login">Log in</Link>
              </Button>
              <Button asChild variant="inverse" size="sm">
                <Link to="/register">Sign up</Link>
              </Button>
            </>
          )}
        </div>

        <Sheet>
          <SheetTrigger asChild>
            <button
              type="button"
              aria-label="Open menu"
              className="grid size-11 place-items-center rounded-md text-fg-secondary shadow-neu md:hidden"
            >
              <Menu className="size-5" aria-hidden />
            </button>
          </SheetTrigger>
          <SheetContent>
            <SheetTitle className="sr-only">Menu</SheetTitle>
            <SheetDescription className="sr-only">Site navigation</SheetDescription>
            <div className="mb-8 px-1.5 pt-1">
              <Logo />
            </div>
            <nav aria-label="Mobile" className="flex flex-col gap-1">
              {LINKS.map((link) => (
                <SheetClose asChild key={link.href}>
                  <a href={link.href} className="flex min-h-11 items-center rounded-md px-3 font-medium text-fg-secondary hover:bg-elevated hover:text-fg">
                    {link.label}
                  </a>
                </SheetClose>
              ))}
            </nav>
            <div className="mt-auto flex flex-col gap-3 pb-2">
              <ThemeToggle className="self-start" />
              {signedIn ? (
                <Button asChild variant="inverse">
                  <Link to={homePathFor(user.role)}>Open RideX</Link>
                </Button>
              ) : (
                <>
                  <Button asChild variant="secondary">
                    <Link to="/login">Log in</Link>
                  </Button>
                  <Button asChild variant="inverse">
                    <Link to="/register">Sign up</Link>
                  </Button>
                </>
              )}
            </div>
          </SheetContent>
        </Sheet>
      </div>
    </header>
  )
}

function Footer() {
  return (
    <footer className="glass rounded-none border-x-0 border-b-0">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-12 sm:px-6 md:grid-cols-[1.4fr_1fr_1fr_1fr]">
        <div>
          <Logo />
          <p className="mt-4 max-w-xs text-sm text-fg-secondary">
            People move the world. We help them move better.
          </p>
        </div>
        <nav aria-label="Product">
          <p className="eyebrow mb-3">Product</p>
          <ul className="space-y-2 text-sm text-fg-secondary">
            <li><a className="hover:text-fg" href="/#how">How it works</a></li>
            <li><a className="hover:text-fg" href="/#rides">For riders</a></li>
            <li><a className="hover:text-fg" href="/#drivers">For drivers</a></li>
            <li><a className="hover:text-fg" href="/#safety">Safety</a></li>
          </ul>
        </nav>
        <nav aria-label="Account">
          <p className="eyebrow mb-3">Account</p>
          <ul className="space-y-2 text-sm text-fg-secondary">
            <li><Link className="hover:text-fg" to="/login">Log in</Link></li>
            <li><Link className="hover:text-fg" to="/register">Sign up to ride</Link></li>
            <li><Link className="hover:text-fg" to="/register/driver">Drive with RideX</Link></li>
          </ul>
        </nav>
        <div>
          <p className="eyebrow mb-3">About this project</p>
          <p className="text-sm text-fg-secondary">
            RideX is a portfolio project: a full-stack ride-hailing platform built to demonstrate
            real-world engineering.
          </p>
        </div>
      </div>
      <div className="border-t border-border">
        <p className="mx-auto max-w-7xl px-4 py-5 text-xs text-fg-muted sm:px-6">
          © {new Date().getFullYear()} RideX. Demonstration project, not a live ride service.
        </p>
      </div>
    </footer>
  )
}

export function PublicLayout() {
  return (
    <div className="min-h-screen">
      <PublicNav />
      <Outlet />
      <Footer />
    </div>
  )
}
