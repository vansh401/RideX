import { zodResolver } from '@hookform/resolvers/zod'
import { MapPin, Phone, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { RiderProfile, SavedPlace } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { TextField } from '@/components/ui/text-field'
import {
  emergencyContactSchema,
  savedPlaceSchema,
  type EmergencyContactValues,
  type SavedPlaceValues,
} from './schemas'

function EmergencyContactCard({
  profile,
  onChange,
}: {
  profile: RiderProfile
  onChange: (profile: RiderProfile) => void
}) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<EmergencyContactValues>({
    resolver: zodResolver(emergencyContactSchema),
    mode: 'onTouched',
    defaultValues: {
      name: profile.emergencyContact?.name ?? '',
      phone: profile.emergencyContact?.phone ?? '',
    },
  })

  async function save(values: EmergencyContactValues) {
    setFormError(null)
    const cleared = values.name === ''
    try {
      const { data } = await api.put<RiderProfile>('/api/riders/me', {
        emergencyContactName: cleared ? null : values.name,
        emergencyContactPhone: cleared ? null : values.phone,
      })
      onChange(data)
      reset({ name: data.emergencyContact?.name ?? '', phone: data.emergencyContact?.phone ?? '' })
      toast.success(cleared ? 'Emergency contact removed' : 'Emergency contact saved')
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Emergency contact</CardTitle>
        <CardDescription>Someone we can reach if there is a problem on a trip. Leave both fields empty to remove it.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={handleSubmit(save)} noValidate className="flex flex-col gap-5">
          {formError && <Alert kind="error">{formError}</Alert>}
          <div className="grid gap-5 sm:grid-cols-2">
            <TextField label="Name" autoComplete="off" error={errors.name?.message} {...register('name')} />
            <TextField
              label="Phone"
              type="tel"
              inputMode="tel"
              autoComplete="off"
              error={errors.phone?.message}
              {...register('phone')}
            />
          </div>
          <div>
            <Button type="submit" loading={isSubmitting}>
              {isSubmitting ? 'Saving…' : 'Save contact'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}

function AddPlaceForm({ onAdded }: { onAdded: (place: SavedPlace) => void }) {
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SavedPlaceValues>({ resolver: zodResolver(savedPlaceSchema), mode: 'onTouched' })

  async function add(values: SavedPlaceValues) {
    setFormError(null)
    try {
      const { data } = await api.post<SavedPlace>('/api/riders/me/places', {
        label: values.label,
        address: values.address,
        latitude: Number(values.latitude),
        longitude: Number(values.longitude),
      })
      onAdded(data)
      reset()
      toast.success(`${data.label} saved`)
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      for (const [field, message] of Object.entries(friendly.fieldErrors)) {
        if (field in values) setError(field as keyof SavedPlaceValues, { type: 'server', message })
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(add)} noValidate className="flex flex-col gap-5 border-t border-border pt-6">
      <h3 className="font-semibold text-fg">Add a place</h3>
      {formError && <Alert kind="error">{formError}</Alert>}
      <div className="grid gap-5 sm:grid-cols-2">
        <TextField label="Name" placeholder="Home, Work…" autoComplete="off" error={errors.label?.message} {...register('label')} />
        <TextField label="Address" autoComplete="off" error={errors.address?.message} {...register('address')} />
        <TextField
          label="Latitude"
          inputMode="decimal"
          placeholder="12.971599"
          autoComplete="off"
          error={errors.latitude?.message}
          {...register('latitude')}
        />
        <TextField
          label="Longitude"
          inputMode="decimal"
          placeholder="77.594566"
          autoComplete="off"
          error={errors.longitude?.message}
          {...register('longitude')}
        />
      </div>
      <p className="text-sm text-fg-muted">Picking a place on a map arrives with the booking screens; for now enter the coordinates.</p>
      <div>
        <Button type="submit" variant="secondary" loading={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Save place'}
        </Button>
      </div>
    </form>
  )
}

function SavedPlacesCard({
  places,
  onChange,
}: {
  places: SavedPlace[]
  onChange: (places: SavedPlace[]) => void
}) {
  async function remove(place: SavedPlace) {
    try {
      await api.delete(`/api/riders/me/places/${place.id}`)
      onChange(places.filter((p) => p.id !== place.id))
      toast.success(`${place.label} removed`)
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Saved places</CardTitle>
        <CardDescription>Quick picks when you book a ride. Up to 10.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        {places.length === 0 ? (
          <EmptyState icon={MapPin} title="No saved places yet" description="Add Home or Work to book faster." />
        ) : (
          <ul className="divide-y divide-border rounded-md border border-border">
            {places.map((place) => (
              <li key={place.id} className="flex items-center gap-4 p-4">
                <span className="grid size-10 shrink-0 place-items-center rounded-md bg-accent-subtle text-accent">
                  <MapPin className="size-5" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="font-medium text-fg">{place.label}</p>
                  <p className="truncate text-sm text-fg-secondary">{place.address}</p>
                  <p className="tabular text-xs text-fg-muted">
                    {place.latitude}, {place.longitude}
                  </p>
                </div>
                <Button variant="ghost" size="icon" aria-label={`Delete ${place.label}`} onClick={() => remove(place)}>
                  <Trash2 className="size-5" aria-hidden />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <AddPlaceForm onAdded={(place) => onChange([...places, place])} />
      </CardContent>
    </Card>
  )
}

/** Emergency contact and saved places of the logged-in rider (GET/PUT /api/riders/me, /places). */
export function RiderProfileSection() {
  const [profile, setProfile] = useState<RiderProfile | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api
      .get<RiderProfile>('/api/riders/me')
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

  if (error) return <Alert kind="error">{error}</Alert>
  if (!profile) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-56" />
        <Skeleton className="h-72" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <EmergencyContactCard key={profile.emergencyContact?.phone ?? 'none'} profile={profile} onChange={setProfile} />
      <SavedPlacesCard
        places={profile.savedPlaces}
        onChange={(savedPlaces) => setProfile({ ...profile, savedPlaces })}
      />
      <p className="flex items-center gap-2 text-sm text-fg-muted">
        <Phone className="size-4" aria-hidden /> Your emergency contact is only used for safety follow-up.
      </p>
    </div>
  )
}
