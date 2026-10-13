import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { UserProfile } from '@/api/types'

/** Loads the logged-in user's full profile from GET /api/users/me. */
export function useProfile() {
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .get<UserProfile>('/api/users/me')
      .then((response) => {
        if (!cancelled) setProfile(response.data)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(toFriendlyError(err).message)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { profile, error, loading: !profile && !error }
}
