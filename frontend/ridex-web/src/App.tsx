import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import { ProtectedRoute, PublicOnlyRoute } from '@/auth/RouteGuards'
import { LandingPage } from '@/features/landing/LandingPage'
import { AuthLayout } from '@/layouts/AuthLayout'
import { PublicLayout } from '@/layouts/PublicLayout'
import { NotFoundPage } from '@/features/app/StatusPages'

// The marketing page loads first and fast. Forms (zod, react-hook-form) and the signed-in app
// are separate chunks that load when their route is opened.
const LoginPage = lazy(() => import('@/features/auth/LoginPage').then((m) => ({ default: m.LoginPage })))
const RegisterPage = lazy(() => import('@/features/auth/RegisterPage').then((m) => ({ default: m.RegisterPage })))
const ForgotPasswordPage = lazy(() =>
  import('@/features/auth/ForgotPasswordPage').then((m) => ({ default: m.ForgotPasswordPage })),
)
const ResetPasswordPage = lazy(() =>
  import('@/features/auth/ResetPasswordPage').then((m) => ({ default: m.ResetPasswordPage })),
)
const VerifyEmailPage = lazy(() =>
  import('@/features/auth/VerifyEmailPage').then((m) => ({ default: m.VerifyEmailPage })),
)
const AppShell = lazy(() => import('@/layouts/AppShell').then((m) => ({ default: m.AppShell })))
const ProfilePage = lazy(() => import('@/features/app/ProfilePage').then((m) => ({ default: m.ProfilePage })))
const DashboardRedirect = lazy(() =>
  import('@/features/app/DashboardRedirect').then((m) => ({ default: m.DashboardRedirect })),
)
const RiderHome = lazy(() => import('@/features/rider/RiderHome').then((m) => ({ default: m.RiderHome })))
const BookRidePage = lazy(() => import('@/features/rider/BookRidePage').then((m) => ({ default: m.BookRidePage })))
const RideDetailPage = lazy(() => import('@/features/rides/RideDetailPage').then((m) => ({ default: m.RideDetailPage })))
const RideHistoryPage = lazy(() => import('@/features/rides/RideHistoryPage').then((m) => ({ default: m.RideHistoryPage })))
const DriverDashboard = lazy(() => import('@/features/driver/DriverDashboard').then((m) => ({ default: m.DriverDashboard })))
const DocumentsPage = lazy(() => import('@/features/driver/DocumentsPage').then((m) => ({ default: m.DocumentsPage })))
const VehiclesPage = lazy(() => import('@/features/driver/VehiclesPage').then((m) => ({ default: m.VehiclesPage })))
const AdminOverview = lazy(() => import('@/features/admin/AdminOverview').then((m) => ({ default: m.AdminOverview })))
const AdminDriversPage = lazy(() => import('@/features/admin/AdminDriversPage').then((m) => ({ default: m.AdminDriversPage })))
const AdminDriverDetailPage = lazy(() => import('@/features/admin/AdminDriversPage').then((m) => ({ default: m.AdminDriverDetailPage })))
const AdminUsersPage = lazy(() => import('@/features/admin/AdminUsersPage').then((m) => ({ default: m.AdminUsersPage })))
const AdminVehiclesPage = lazy(() => import('@/features/admin/AdminVehiclesPage').then((m) => ({ default: m.AdminVehiclesPage })))
const NotificationsPage = lazy(() =>
  import('@/features/notifications/NotificationsPage').then((m) => ({ default: m.NotificationsPage })),
)
const AdminPaymentsPage = lazy(() =>
  import('@/features/admin/AdminPaymentsPage').then((m) => ({ default: m.AdminPaymentsPage })),
)
const WalletPage = lazy(() => import('@/features/rider/WalletPage').then((m) => ({ default: m.WalletPage })))
const EarningsPage = lazy(() => import('@/features/driver/EarningsPage').then((m) => ({ default: m.EarningsPage })))
const AdminCouponsPage = lazy(() => import('@/features/admin/AdminCouponsPage').then((m) => ({ default: m.AdminCouponsPage })))
const AdminComplaintsPage = lazy(() => import('@/features/admin/AdminComplaintsPage').then((m) => ({ default: m.AdminComplaintsPage })))
const AdminAnalyticsPage = lazy(() => import('@/features/admin/AdminAnalyticsPage').then((m) => ({ default: m.AdminAnalyticsPage })))
const AdminFraudPage = lazy(() => import('@/features/admin/AdminFraudPage').then((m) => ({ default: m.AdminFraudPage })))
const AdminEventsPage = lazy(() => import('@/features/admin/AdminEventsPage').then((m) => ({ default: m.AdminEventsPage })))
const ForbiddenPage = lazy(() => import('@/features/app/StatusPages').then((m) => ({ default: m.ForbiddenPage })))

function App() {
  return (
    <Suspense fallback={<p className="grid min-h-screen place-items-center text-fg-muted">Loading…</p>}>
      <Routes>
        {/* Marketing */}
        <Route element={<PublicLayout />}>
          <Route path="/" element={<LandingPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>

        {/* Authentication screens */}
        <Route element={<AuthLayout />}>
          {/* Email links land here whether or not you are logged in */}
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />

          <Route element={<PublicOnlyRoute />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage role="RIDER" />} />
            <Route path="/register/driver" element={<RegisterPage role="DRIVER" />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          </Route>
        </Route>

        {/* The application: needs a login; each area also needs its role */}
        <Route element={<ProtectedRoute />}>
          <Route element={<AppShell />}>
            <Route path="/dashboard" element={<DashboardRedirect />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/forbidden" element={<ForbiddenPage />} />
            <Route path="/rides/:rideId" element={<RideDetailPage />} />

            <Route element={<ProtectedRoute roles={['RIDER']} />}>
              <Route path="/rider" element={<RiderHome />} />
              <Route path="/rider/book" element={<BookRidePage />} />
              <Route path="/rider/wallet" element={<WalletPage />} />
              <Route path="/rider/rides" element={<RideHistoryPage eyebrow="Rider" title="My rides" />} />
            </Route>
            <Route element={<ProtectedRoute roles={['DRIVER']} />}>
              <Route path="/driver" element={<DriverDashboard />} />
              <Route path="/driver/documents" element={<DocumentsPage />} />
              <Route path="/driver/vehicles" element={<VehiclesPage />} />
              <Route path="/driver/earnings" element={<EarningsPage />} />
              <Route path="/driver/history" element={<RideHistoryPage eyebrow="Driver" title="Ride history" />} />
            </Route>
            <Route element={<ProtectedRoute roles={['ADMIN']} />}>
              <Route path="/admin" element={<AdminOverview />} />
              <Route path="/admin/drivers" element={<AdminDriversPage />} />
              <Route path="/admin/drivers/:driverId" element={<AdminDriverDetailPage />} />
              <Route path="/admin/users" element={<AdminUsersPage />} />
              <Route path="/admin/payments" element={<AdminPaymentsPage />} />
              <Route path="/admin/coupons" element={<AdminCouponsPage />} />
              <Route path="/admin/complaints" element={<AdminComplaintsPage />} />
              <Route path="/admin/reports" element={<AdminAnalyticsPage />} />
              <Route path="/admin/fraud" element={<AdminFraudPage />} />
              <Route path="/admin/events" element={<AdminEventsPage />} />
              <Route path="/admin/vehicles" element={<AdminVehiclesPage />} />
              <Route path="/admin/rides" element={<RideHistoryPage path="/api/admin/rides" eyebrow="Admin" title="All rides" />} />
            </Route>
          </Route>
        </Route>
      </Routes>
    </Suspense>
  )
}

export default App
