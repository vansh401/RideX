import { Navigate } from 'react-router-dom'
import { useAuth } from '@/auth/useAuth'
import { homePathFor } from '@/routes/navigation'

/** /dashboard sends each role to its own area. */
export function DashboardRedirect() {
  const { user } = useAuth()
  if (!user) return null // ProtectedRoute handles the logged-out case
  return <Navigate to={homePathFor(user.role)} replace />
}
