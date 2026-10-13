import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import type { Role, UserProfile } from '../api/types'
import { Alert } from '../components/Alert'

const COPY: Record<Role, { title: string; text: string }> = {
  RIDER: { title: 'Rider home', text: 'Ride requests, fare estimates and your trip history arrive in Phase 3.' },
  DRIVER: { title: 'Driver home', text: 'Your documents, vehicles and incoming ride requests arrive in Phase 3.' },
  ADMIN: { title: 'Admin console', text: 'Driver approval, users and ride oversight arrive in Phase 3.' },
}

/**
 * Placeholder landing page per role. It already calls a protected API (/api/users/me), which
 * is what exercises the access-token + auto-refresh machinery end to end.
 */
export function RoleHomePage({ role }: { role: Role }) {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .get<UserProfile>('/api/users/me')
      .then((response) => {
        if (!cancelled) setProfile(response.data)
      })
      .catch((err) => {
        if (!cancelled) setError(toFriendlyError(err).message)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <section className="card">
      <h1>{COPY[role].title}</h1>
      <p>{COPY[role].text}</p>
      {error && <Alert kind="error">{error}</Alert>}
      {profile && (
        <dl className="details">
          <dt>Name</dt>
          <dd>{profile.fullName}</dd>
          <dt>Email</dt>
          <dd>{profile.email}</dd>
          <dt>Phone</dt>
          <dd>{profile.phone}</dd>
          <dt>Account status</dt>
          <dd>{profile.status}</dd>
        </dl>
      )}
    </section>
  )
}
