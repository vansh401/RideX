import { Navigate, Outlet, useLocation } from 'react-router-dom'
import type { Role } from '../api/types'
import { useAuth } from './useAuth'

/**
 * Routes under this element need a logged-in user, and optionally one of `roles`.
 *
 * Remember: this is UX, not security. The backend checks the role again on every call;
 * hiding a page here only avoids showing someone a screen that would fail anyway.
 */
export function ProtectedRoute({ roles }: { roles?: Role[] }) {
  const { status, user } = useAuth()
  const location = useLocation()

  if (status === 'loading') {
    return <p className="center muted">Loading…</p>
  }
  if (status === 'anonymous' || !user) {
    // Remember where they wanted to go, so login can bring them back
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (roles && !roles.includes(user.role)) {
    return <Navigate to="/forbidden" replace />
  }
  return <Outlet />
}

/** Login/register pages make no sense when you are already logged in. */
export function PublicOnlyRoute() {
  const { status } = useAuth()

  if (status === 'loading') {
    return <p className="center muted">Loading…</p>
  }
  if (status === 'authenticated') {
    return <Navigate to="/dashboard" replace />
  }
  return <Outlet />
}
