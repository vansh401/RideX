import { zodResolver } from '@hookform/resolvers/zod'
import { KeyRound, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { PasswordField } from '@/components/ui/text-field'
import { resetSchema, type ResetValues } from './schemas'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [formError, setFormError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResetValues>({ resolver: zodResolver(resetSchema), mode: 'onTouched' })

  async function onSubmit(values: ResetValues) {
    setFormError(null)
    try {
      await api.post('/api/auth/reset-password', { token, newPassword: values.password })
      setDone(true)
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      if (friendly.fieldErrors.newPassword) {
        setError('password', { type: 'server', message: friendly.fieldErrors.newPassword })
      }
    }
  }

  if (!token) {
    return (
      <div>
        <span className="grid size-14 place-items-center rounded-lg bg-danger/10 text-danger">
          <KeyRound className="size-7" aria-hidden />
        </span>
        <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">This link is incomplete</h1>
        <p className="mt-2 text-fg-secondary">Request a new password reset link.</p>
        <Button asChild size="lg" className="mt-8 w-full">
          <Link to="/forgot-password">Request a new link</Link>
        </Button>
      </div>
    )
  }

  if (done) {
    return (
      <div>
        <span className="grid size-14 place-items-center rounded-lg bg-success/10 text-success">
          <ShieldCheck className="size-7" aria-hidden />
        </span>
        <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Password updated</h1>
        <p className="mt-2 text-fg-secondary">Other devices were logged out. Log in with your new password.</p>
        <Button asChild size="lg" className="mt-8 w-full">
          <Link to="/login">Log in</Link>
        </Button>
      </div>
    )
  }

  return (
    <div>
      <h1 className="text-3xl font-bold tracking-tight text-fg">Choose a new password</h1>
      <p className="mt-2 text-fg-secondary">Pick something you have not used elsewhere.</p>

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="mt-8 flex flex-col gap-5">
        {formError && <Alert kind="error">{formError}</Alert>}
        <PasswordField
          label="New password"
          autoComplete="new-password"
          hint="At least 8 characters, with a letter and a digit"
          error={errors.password?.message}
          {...register('password')}
        />
        <PasswordField
          label="Repeat new password"
          autoComplete="new-password"
          error={errors.confirm?.message}
          {...register('confirm')}
        />
        <Button type="submit" size="lg" loading={isSubmitting}>
          {isSubmitting ? 'Saving…' : 'Change password'}
        </Button>
      </form>
    </div>
  )
}
