import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/auth/useAuth'
import { DriverProfileSection } from '@/features/profile/DriverProfileSection'
import { RiderProfileSection } from '@/features/profile/RiderProfileSection'
import { formatDateTime } from '@/lib/format'
import { useProfile } from './useProfile'

export function ProfilePage() {
  const { user } = useAuth()
  const { profile, error, loading } = useProfile()

  return (
    <>
      <PageHeader eyebrow="Account" title="Your profile" />
      {error && <Alert kind="error">{error}</Alert>}

      <div className="max-w-3xl space-y-6">
        <Card>
          <CardContent>
            {loading || !profile ? (
              <div className="space-y-4">
                <Skeleton className="h-6 w-48" />
                <Skeleton className="h-6 w-64" />
                <Skeleton className="h-6 w-40" />
              </div>
            ) : (
              <dl className="grid grid-cols-[max-content_1fr] gap-x-8 gap-y-4">
                <dt className="text-fg-muted">Name</dt>
                <dd className="font-medium text-fg">{profile.fullName}</dd>
                <dt className="text-fg-muted">Email</dt>
                <dd className="font-medium text-fg">{profile.email}</dd>
                <dt className="text-fg-muted">Phone</dt>
                <dd className="tabular font-medium text-fg">{profile.phone}</dd>
                <dt className="text-fg-muted">Role</dt>
                <dd>
                  <Badge tone="accent">{profile.role}</Badge>
                </dd>
                <dt className="text-fg-muted">Status</dt>
                <dd>
                  <Badge tone={profile.status === 'ACTIVE' ? 'success' : 'warning'}>{profile.status}</Badge>
                </dd>
                <dt className="text-fg-muted">Joined</dt>
                <dd className="font-medium text-fg">{formatDateTime(profile.createdAt)}</dd>
              </dl>
            )}
          </CardContent>
        </Card>

        {/* Each role has its own extra details; admins have none */}
        {user?.role === 'RIDER' && <RiderProfileSection />}
        {user?.role === 'DRIVER' && <DriverProfileSection />}
      </div>
    </>
  )
}
