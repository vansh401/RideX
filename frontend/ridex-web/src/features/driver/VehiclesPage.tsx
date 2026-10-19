import { zodResolver } from '@hookform/resolvers/zod'
import { Car } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { Vehicle } from '@/api/types'
import { EmptyState } from '@/components/brand/EmptyState'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { SelectField } from '@/components/ui/select'
import { TextField } from '@/components/ui/text-field'
import { CATEGORY_LABEL } from '@/lib/rides'
import { vehicleSchema, type VehicleInput, type VehicleValues } from './vehicleSchema'


export function VehiclesPage() {
  const [vehicles, setVehicles] = useState<Vehicle[] | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<VehicleInput, unknown, VehicleValues>({
    resolver: zodResolver(vehicleSchema),
    mode: 'onTouched',
    defaultValues: { category: 'CAB', make: '', model: '', color: '', registrationNumber: '', manufactureYear: new Date().getFullYear(), seats: 4 },
  })

  const reload = useCallback(async () => {
    try {
      setVehicles((await api.get<Vehicle[]>('/api/drivers/me/vehicles')).data)
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load
    void reload()
  }, [reload])

  async function add(values: VehicleValues) {
    setFormError(null)
    try {
      await api.post('/api/drivers/me/vehicles', values)
      toast.success('Vehicle added')
      reset()
      await reload()
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      for (const [field, message] of Object.entries(friendly.fieldErrors)) {
        if (field in values) setError(field as keyof VehicleValues, { type: 'server', message })
      }
    }
  }

  async function act(run: () => Promise<unknown>, done: string) {
    try {
      await run()
      toast.success(done)
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <>
      <PageHeader eyebrow="Driver" title="Vehicles" description="Only your active vehicle receives ride requests." />
      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,26rem)]">
        <div className="flex flex-col gap-3">
          {vehicles?.length === 0 && (
            <EmptyState icon={Car} title="No vehicles yet" description="Add your vehicle using the form." />
          )}
          {vehicles?.map((v) => (
            <Card key={v.id}>
              <CardContent className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p className="font-semibold text-fg">{v.color} {v.make} {v.model}</p>
                  <p className="tabular text-sm text-fg-muted">
                    {v.registrationNumber} · {CATEGORY_LABEL[v.category]} · {v.seats} seats · {v.manufactureYear}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {v.active ? (
                    <Badge tone="success">Active</Badge>
                  ) : (
                    <Button size="sm" variant="secondary" onClick={() => act(() => api.post(`/api/drivers/me/vehicles/${v.id}/activate`), 'Vehicle activated')}>
                      Make active
                    </Button>
                  )}
                  <Button size="sm" variant="ghost" onClick={() => act(() => api.delete(`/api/drivers/me/vehicles/${v.id}`), 'Vehicle removed')}>
                    Remove
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>

        <Card>
          <CardContent>
            <p className="mb-4 font-semibold text-fg">Add a vehicle</p>
            <form onSubmit={handleSubmit(add)} noValidate className="flex flex-col gap-4">
              {formError && <Alert kind="error">{formError}</Alert>}
              <SelectField label="Type" {...register('category')}>
                <option value="BIKE">Bike</option>
                <option value="AUTO">Auto</option>
                <option value="CAB">Cab</option>
              </SelectField>
              <div className="grid grid-cols-2 gap-3">
                <TextField label="Make" error={errors.make?.message} {...register('make')} />
                <TextField label="Model" error={errors.model?.message} {...register('model')} />
              </div>
              <TextField label="Colour" error={errors.color?.message} {...register('color')} />
              <TextField label="Registration number" error={errors.registrationNumber?.message} {...register('registrationNumber')} />
              <div className="grid grid-cols-2 gap-3">
                <TextField label="Year" inputMode="numeric" error={errors.manufactureYear?.message} {...register('manufactureYear')} />
                <TextField label="Seats" inputMode="numeric" error={errors.seats?.message} {...register('seats')} />
              </div>
              <Button type="submit" loading={isSubmitting}>Add vehicle</Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </>
  )
}
