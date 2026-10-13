import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

/** Sends each role to its own area. */
export function DashboardPage() {
  const { user } = useAuth()
  if (!user) {
    return null // ProtectedRoute handles the logged-out case
  }
  return <Navigate to={`/${user.role.toLowerCase()}`} replace />
}
