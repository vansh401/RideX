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
const HomePage = lazy(() => import('@/features/app/HomePage').then((m) => ({ default: m.HomePage })))
const ProfilePage = lazy(() => import('@/features/app/ProfilePage').then((m) => ({ default: m.ProfilePage })))
const DashboardRedirect = lazy(() =>
  import('@/features/app/DashboardRedirect').then((m) => ({ default: m.DashboardRedirect })),
)
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
            <Route path="/forbidden" element={<ForbiddenPage />} />

            <Route element={<ProtectedRoute roles={['RIDER']} />}>
              <Route path="/rider" element={<HomePage role="RIDER" />} />
            </Route>
            <Route element={<ProtectedRoute roles={['DRIVER']} />}>
              <Route path="/driver" element={<HomePage role="DRIVER" />} />
            </Route>
            <Route element={<ProtectedRoute roles={['ADMIN']} />}>
              <Route path="/admin" element={<HomePage role="ADMIN" />} />
            </Route>
          </Route>
        </Route>
      </Routes>
    </Suspense>
  )
}

export default App
