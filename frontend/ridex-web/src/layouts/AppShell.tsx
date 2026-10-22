import { ChevronDown, LogOut, Menu, User as UserIcon } from 'lucide-react'
import { motion } from 'motion/react'
import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import type { Role } from '@/api/types'
import { useAuth } from '@/auth/useAuth'
import { Logo } from '@/components/brand/Logo'
import { PageTransition } from '@/components/brand/PageTransition'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { Tip, TooltipProvider } from '@/components/ui/tooltip'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { cn } from '@/lib/cn'
import { ConnectionBadge } from '@/realtime/ConnectionBadge'
import { RealtimeProvider } from '@/realtime/RealtimeProvider'
import { navigation, type NavItem } from '@/routes/navigation'

const SUBTITLE: Record<Role, string> = {
  RIDER: 'Where are we taking you today?',
  DRIVER: 'Drive safe. Make today count.',
  ADMIN: "Here's what's happening across RideX today.",
}

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')
}

const itemBase =
  'group relative flex min-h-11 items-center gap-3 rounded-md px-3 text-sm font-medium transition-colors duration-(--dur-base) ease-(--ease-standard)'

function NavEntry({ item, showLabels }: { item: NavItem; showLabels: boolean }) {
  const Icon = item.icon
  // The active state is computed here and a plain string class is passed down: a className FUNCTION would be
  // turned into text when the tooltip wrapper (Radix asChild) merges class names.
  const isActive = useLocation().pathname === item.to
  // In the icon rail (lg..xl) labels are hidden; the tooltip supplies the name
  const label = cn(showLabels ? 'inline' : 'hidden xl:inline')

  if (!item.available) {
    return (
      <Tip label={`${item.label}: coming in ${item.when}`}>
        <button type="button" aria-disabled="true" className={cn(itemBase, 'w-full cursor-not-allowed text-fg-muted/70')}>
          <Icon className="size-5 shrink-0" aria-hidden />
          <span className={cn(label, 'flex-1 text-left')}>{item.label}</span>
          <Badge className={cn(label, 'px-2 py-0 text-[11px]')}>Soon</Badge>
        </button>
      </Tip>
    )
  }

  return (
    <Tip label={item.label}>
      <NavLink
        to={item.to}
        end
        className={cn(itemBase, isActive ? 'text-accent' : 'text-fg-secondary hover:bg-white/[0.05] hover:text-fg')}
      >
        {/* The pill slides between items; the edge marker keeps the state from being colour-only */}
        {isActive && (
          <motion.span
            aria-hidden
            layoutId={showLabels ? 'nav-active-drawer' : 'nav-active-rail'}
            transition={{ type: 'spring', stiffness: 500, damping: 40 }}
            className="absolute inset-0 rounded-md bg-accent-subtle shadow-[inset_0_1px_0_rgb(255_255_255/0.06)] ring-1 ring-accent/20"
          >
            <span className="absolute inset-y-2.5 left-0 w-[3px] rounded-r bg-accent shadow-[0_0_10px_var(--accent-glow)]" />
          </motion.span>
        )}
        <Icon className="relative size-5 shrink-0" aria-hidden />
        <span className={cn('relative', label)}>{item.label}</span>
      </NavLink>
    </Tip>
  )
}

function SidebarNav({ role, showLabels }: { role: Role; showLabels: boolean }) {
  return (
    <nav aria-label="Main" className="flex flex-col gap-6">
      {navigation[role].map((group) => (
        <div key={group.label} className="flex flex-col gap-1">
          <p className={cn('eyebrow px-3 pb-1', showLabels ? 'block' : 'hidden xl:block')}>{group.label}</p>
          {group.items.map((item) => (
            <NavEntry key={item.to} item={item} showLabels={showLabels} />
          ))}
        </div>
      ))}
    </nav>
  )
}

export function AppShell() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const [drawerOpen, setDrawerOpen] = useState(false)

  if (!user) return null // ProtectedRoute handles the logged-out case

  async function handleLogout() {
    await logout()
    navigate('/login', { replace: true })
  }

  const bottomTabs = navigation[user.role].flatMap((group) => group.items).filter((item) => item.available)

  return (
    <RealtimeProvider>
    <TooltipProvider delayDuration={150}>
      <div className="min-h-screen">
        {/* Desktop sidebar: labels at xl, icon rail at lg */}
        <aside className="glass glass-float fixed inset-y-3 left-3 z-30 hidden w-[76px] flex-col rounded-xl p-3 lg:flex xl:w-64">
          <div className="mb-6 flex h-12 items-center px-1.5">
            <NavLink to="/" aria-label="RideX home">
              <span className="xl:hidden">
                <Logo showWordmark={false} />
              </span>
              <span className="hidden xl:inline">
                <Logo />
              </span>
            </NavLink>
          </div>
          <div className="flex-1 overflow-y-auto">
            <SidebarNav role={user.role} showLabels={false} />
          </div>
          <button
            type="button"
            onClick={handleLogout}
            className={cn(itemBase, 'w-full text-fg-secondary hover:bg-elevated hover:text-fg')}
          >
            <LogOut className="size-5 shrink-0" aria-hidden />
            <span className="hidden xl:inline">Log out</span>
          </button>
        </aside>

        <div className="lg:pl-[100px] xl:pl-[17.5rem]">
          <header className="glass glass-float sticky top-3 z-20 mx-3 mt-3 flex h-16 items-center gap-3 rounded-xl px-4 sm:mx-6 sm:px-5 lg:mx-6">
            {/* Below lg the sidebar becomes a drawer */}
            <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
              <SheetTrigger asChild>
                <button
                  type="button"
                  aria-label="Open menu"
                  className="grid size-11 place-items-center rounded-md text-fg-secondary shadow-neu hover:text-fg lg:hidden"
                >
                  <Menu className="size-5" aria-hidden />
                </button>
              </SheetTrigger>
              <SheetContent>
                <SheetTitle className="sr-only">Navigation</SheetTitle>
                <SheetDescription className="sr-only">Main navigation</SheetDescription>
                <div className="mb-6 px-1.5 pt-1">
                  <Logo />
                </div>
                <div className="flex-1 overflow-y-auto" onClick={() => setDrawerOpen(false)}>
                  <SidebarNav role={user.role} showLabels />
                </div>
              </SheetContent>
            </Sheet>

            <div className="min-w-0 flex-1">
              <p className="truncate text-base font-semibold leading-5 text-fg">
                {greeting()}, {user.fullName.split(' ')[0]}
              </p>
              <p className="hidden truncate text-sm text-fg-muted sm:block">{SUBTITLE[user.role]}</p>
            </div>

            <ConnectionBadge />
            <NotificationBell />

            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex min-h-11 items-center gap-2.5 rounded-md px-2 hover:bg-elevated"
                  aria-label="Account menu"
                >
                  <span className="grid size-9 place-items-center rounded-full bg-gradient-to-br from-accent/30 to-accent/5 text-sm font-bold text-accent ring-1 ring-accent/30">
                    {initials(user.fullName)}
                  </span>
                  <span className="hidden text-left sm:block">
                    <span className="block text-sm font-semibold leading-4 text-fg">{user.fullName}</span>
                    <span className="block text-xs capitalize text-fg-muted">{user.role.toLowerCase()}</span>
                  </span>
                  <ChevronDown className="hidden size-4 text-fg-muted sm:block" aria-hidden />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>{user.email}</DropdownMenuLabel>
                <DropdownMenuItem onSelect={() => navigate('/profile')}>
                  <UserIcon className="size-4" aria-hidden /> Profile
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={handleLogout}>
                  <LogOut className="size-4" aria-hidden /> Log out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </header>

          <main className="mx-auto w-full max-w-6xl px-4 pb-28 pt-8 sm:px-6 md:pb-12">
            <PageTransition>
              <Outlet />
            </PageTransition>
          </main>
        </div>

        {/* Phones: bottom tabs for the screens that actually exist */}
        <nav
          aria-label="Quick navigation"
          className="glass fixed inset-x-0 bottom-0 z-30 flex justify-around border-x-0 border-b-0 px-2 pb-[env(safe-area-inset-bottom)] md:hidden"
        >
          {bottomTabs.slice(0, 5).map((item) => {
            const Icon = item.icon
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end
                className={({ isActive }) =>
                  cn(
                    'flex min-h-14 flex-1 flex-col items-center justify-center gap-0.5 text-xs font-medium',
                    isActive ? 'text-accent' : 'text-fg-muted',
                  )
                }
              >
                <Icon className="size-5" aria-hidden />
                {item.label}
              </NavLink>
            )
          })}
        </nav>
      </div>
    </TooltipProvider>
    </RealtimeProvider>
  )
}
