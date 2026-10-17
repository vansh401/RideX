import { zodResolver } from '@hookform/resolvers/zod'
import { ClipboardCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { ApprovalStatus, DriverProfile } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import { formatDate } from '@/lib/format'
import { licenceSchema, todayIso, type LicenceValues } from './schemas'

const STATUS: Record<ApprovalStatus, { label: string; tone: 'warning' | 'success' | 'danger'; text: string }> = {
  PENDING_APPROVAL: {
    label: 'Pending approval',
    tone: 'warning',
    text: 'The RideX team reviews every new driver before they can take rides. You can still correct your details while you wait.',
  },
  APPROVED: { label: 'Approved', tone: 'success', text: 'You are approved to drive.' },
  REJECTED: { label: 'Rejected', tone: 'danger', text: 'Your details were not accepted.' },
}

function LicenceForm({
  profile,
  onSaved,
}: {
  profile: DriverProfile | null
  onSaved: (profile: DriverProfile) => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const editing = profile !== null
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LicenceValues>({
    resolver: zodResolver(licenceSchema),
    mode: 'onTouched',
    defaultValues: {
      licenseNumber: profile?.licenseNumber ?? '',
      licenseExpiry: profile?.licenseExpiry ?? '',
    },
  })

  async function save(values: LicenceValues) {
    setFormError(null)
    try {
      const request = { licenseNumber: values.licenseNumber, licenseExpiry: values.licenseExpiry }
      const { data } = editing
        ? await api.put<DriverProfile>('/api/drivers/me', request)
        : await api.post<DriverProfile>('/api/drivers/me', request)
      onSaved(data)
      toast.success(editing ? 'Licence details updated' : 'Profile created and sent for review')
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      for (const [field, message] of Object.entries(friendly.fieldErrors)) {
        if (field in values) setError(field as keyof LicenceValues, { type: 'server', message })
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(save)} noValidate className="flex flex-col gap-5">
      {formError && <Alert kind="error">{formError}</Alert>}
      <div className="grid gap-5 sm:grid-cols-2">
        <TextField
          label="Licence number"
          autoComplete="off"
          error={errors.licenseNumber?.message}
          {...register('licenseNumber')}
        />
        <TextField
          label="Licence expiry date"
          type="date"
          min={todayIso()}
          error={errors.licenseExpiry?.message}
          {...register('licenseExpiry')}
        />
      </div>
      <div>
        <Button type="submit" loading={isSubmitting}>
          {isSubmitting ? 'Saving…' : editing ? 'Update details' : 'Submit for review'}
        </Button>
      </div>
    </form>
  )
}

/** Licence details and review status of the logged-in driver (GET/POST/PUT /api/drivers/me). */
export function DriverProfileSection() {
  // undefined = still loading, null = no profile yet
  const [profile, setProfile] = useState<DriverProfile | null | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .get<DriverProfile>('/api/drivers/me')
      .then((response) => {
        if (!cancelled) setProfile(response.data)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const friendly = toFriendlyError(err)
        if (friendly.status === 404) setProfile(null) // not an error: they just have not started
        else setError(friendly.message)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (error) return <Alert kind="error">{error}</Alert>
  if (profile === undefined) return <Skeleton className="h-72" />

  if (profile === null) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Become a RideX driver</CardTitle>
          <CardDescription>
            Add your licence details to start. Vehicle details and documents come next, then a short review.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <LicenceForm profile={null} onSaved={setProfile} />
        </CardContent>
      </Card>
    )
  }

  const status = STATUS[profile.approvalStatus]
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <CardTitle className="flex items-center gap-2">
            <ClipboardCheck className="size-5 text-accent" aria-hidden /> Driver verification
          </CardTitle>
          <Badge tone={status.tone}>{status.label}</Badge>
        </div>
        <CardDescription>{status.text}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {profile.approvalStatus === 'PENDING_APPROVAL' ? (
          <LicenceForm key={profile.licenseNumber + profile.licenseExpiry} profile={profile} onSaved={setProfile} />
        ) : (
          <dl className="grid grid-cols-[max-content_1fr] gap-x-8 gap-y-3">
            <dt className="text-fg-muted">Licence number</dt>
            <dd className="font-medium text-fg">{profile.licenseNumber}</dd>
            <dt className="text-fg-muted">Expires</dt>
            <dd className="font-medium text-fg">{formatDate(profile.licenseExpiry)}</dd>
          </dl>
        )}
      </CardContent>
    </Card>
  )
}
