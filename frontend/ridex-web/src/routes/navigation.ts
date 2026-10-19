import {
  BadgeCheck,
  BarChart3,
  Bell,
  Car,
  FileText,
  Gift,
  History,
  Home,
  LifeBuoy,
  Ticket,
  User,
  Users,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import type { Role } from '@/api/types'

export interface NavItem {
  label: string
  to: string
  icon: LucideIcon
  /** false = the backend feature does not exist yet; shown disabled with `when` as the reason */
  available: boolean
  when?: string
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

const profile: NavItem = { label: 'Profile', to: '/profile', icon: User, available: true }

// One config drives the sidebar, the mobile drawer and the bottom tabs.
// The backend still authorises every request; this only decides what to show.
export const navigation: Record<Role, NavGroup[]> = {
  RIDER: [
    {
      label: 'Main menu',
      items: [
        { label: 'Home', to: '/rider', icon: Home, available: true },
        { label: 'Book a ride', to: '/rider/book', icon: Car, available: true },
        { label: 'My rides', to: '/rider/rides', icon: History, available: true },
        { label: 'Wallet', to: '/rider/wallet', icon: Wallet, available: false, when: 'Phase 14' },
        { label: 'Offers', to: '/rider/offers', icon: Gift, available: false, when: 'Step 14.3' },
        { label: 'Notifications', to: '/rider/notifications', icon: Bell, available: false, when: 'Step 5.5' },
        profile,
      ],
    },
  ],
  DRIVER: [
    {
      label: 'Main menu',
      items: [
        { label: 'Dashboard', to: '/driver', icon: Home, available: true },
        { label: 'Ride history', to: '/driver/history', icon: History, available: true },
        { label: 'Earnings', to: '/driver/earnings', icon: BarChart3, available: false, when: 'Step 14.5' },
        { label: 'Documents', to: '/driver/documents', icon: FileText, available: true },
        { label: 'Vehicles', to: '/driver/vehicles', icon: Car, available: true },
        profile,
      ],
    },
  ],
  ADMIN: [
    {
      label: 'Management',
      items: [
        { label: 'Dashboard', to: '/admin', icon: Home, available: true },
        { label: 'Users', to: '/admin/users', icon: Users, available: true },
        { label: 'Drivers', to: '/admin/drivers', icon: BadgeCheck, available: true },
        { label: 'Vehicles', to: '/admin/vehicles', icon: Car, available: true },
        { label: 'Rides', to: '/admin/rides', icon: History, available: true },
        { label: 'Payments', to: '/admin/payments', icon: Wallet, available: false, when: 'Step 6.5' },
        { label: 'Coupons', to: '/admin/coupons', icon: Ticket, available: false, when: 'Step 14.3' },
        { label: 'Complaints', to: '/admin/complaints', icon: LifeBuoy, available: false, when: 'Step 14.7' },
        { label: 'Reports', to: '/admin/reports', icon: BarChart3, available: false, when: 'Step 14.6' },
        profile,
      ],
    },
  ],
}

export function homePathFor(role: Role): string {
  return `/${role.toLowerCase()}`
}
