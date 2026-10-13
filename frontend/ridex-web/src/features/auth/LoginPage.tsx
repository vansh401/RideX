import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { useAuth } from '@/auth/useAuth'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { PasswordField, TextField } from '@/components/ui/text-field'
import { loginSchema, type LoginValues } from './schemas'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  const [formError, setFormError] = useState<string | null>(null)
  const [needsVerification, setNeedsVerification] = useState(false)

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), mode: 'onTouched' })

  async function onSubmit(values: LoginValues) {
    setFormError(null)
    setNeedsVerification(false)
    try {
      await login(values.email, values.password)
      navigate(from, { replace: true })
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      setNeedsVerification(friendly.status === 403 && /verify/i.test(friendly.message))
    }
  }

  async function resendVerification() {
    try {
      await api.post('/api/auth/resend-verification', { email: getValues('email') })
      toast.success('If the account is waiting for verification, a new email is on its way.')
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <div>
      <h1 className="text-3xl font-bold tracking-tight text-fg">Welcome back</h1>
      <p className="mt-2 text-fg-secondary">Log in to continue to RideX.</p>

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="mt-8 flex flex-col gap-5">
        {formError && (
          <Alert kind="error">
            {formError}
            {needsVerification && (
              <>
                {' '}
                <button type="button" onClick={resendVerification} className="font-semibold underline underline-offset-2">
                  Resend verification email
                </button>
              </>
            )}
          </Alert>
        )}

        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          inputMode="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <PasswordField
          label="Password"
          autoComplete="current-password"
          error={errors.password?.message}
          labelAside={
            <Link to="/forgot-password" className="text-sm text-accent hover:underline">
              Forgot password?
            </Link>
          }
          {...register('password')}
        />

        <Button type="submit" size="lg" loading={isSubmitting}>
          {isSubmitting ? 'Logging in…' : 'Log in'}
        </Button>
      </form>

      <p className="mt-8 text-center text-sm text-fg-secondary">
        New to RideX?{' '}
        <Link to="/register" className="font-semibold text-accent hover:underline">
          Create an account
        </Link>
        <span className="mx-2 text-fg-muted">·</span>
        <Link to="/register/driver" className="font-semibold text-accent hover:underline">
          Drive with us
        </Link>
      </p>
    </div>
  )
}
