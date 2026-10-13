import { zodResolver } from '@hookform/resolvers/zod'
import { MailCheck } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { PasswordField, TextField } from '@/components/ui/text-field'
import { registerSchema, type RegisterValues } from './schemas'

type SignupRole = 'RIDER' | 'DRIVER'

const COPY: Record<SignupRole, { title: string; subtitle: string; cta: string }> = {
  RIDER: {
    title: 'Create your rider account',
    subtitle: 'Book rides across the city in a few taps.',
    cta: 'Create account',
  },
  DRIVER: {
    title: 'Drive with RideX',
    subtitle: 'Start with your account. You will add your licence and vehicle right after you log in.',
    cta: 'Create driver account',
  },
}

export function RegisterPage({ role }: { role: SignupRole }) {
  const copy = COPY[role]
  const [formError, setFormError] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({ resolver: zodResolver(registerSchema), mode: 'onTouched' })

  async function onSubmit(values: RegisterValues) {
    setFormError(null)
    try {
      await api.post('/api/auth/register', { ...values, role })
      setSentTo(values.email)
    } catch (err) {
      const friendly = toFriendlyError(err)
      setFormError(friendly.message)
      // Show the server's per-field messages next to the matching inputs
      for (const [field, message] of Object.entries(friendly.fieldErrors)) {
        if (field in values) {
          setError(field as keyof RegisterValues, { type: 'server', message })
        }
      }
    }
  }

  if (sentTo) {
    return (
      <div>
        <span className="grid size-14 place-items-center rounded-lg bg-accent-subtle text-accent">
          <MailCheck className="size-7" aria-hidden />
        </span>
        <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Check your email</h1>
        <p className="mt-2 text-fg-secondary">
          We sent a verification link to <strong className="text-fg">{sentTo}</strong>. Open it to activate your
          account, then log in.
        </p>
        <p className="mt-2 text-sm text-fg-muted">The link works once and expires after 15 minutes.</p>
        <Button asChild size="lg" className="mt-8 w-full">
          <Link to="/login">Go to log in</Link>
        </Button>
      </div>
    )
  }

  return (
    <div>
      <h1 className="text-3xl font-bold tracking-tight text-fg">{copy.title}</h1>
      <p className="mt-2 text-fg-secondary">{copy.subtitle}</p>

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="mt-8 flex flex-col gap-5">
        {formError && <Alert kind="error">{formError}</Alert>}

        <TextField label="Full name" autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          inputMode="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <TextField
          label="Phone"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          hint="10 to 15 digits, with an optional leading +"
          error={errors.phone?.message}
          {...register('phone')}
        />
        <PasswordField
          label="Password"
          autoComplete="new-password"
          hint="At least 8 characters, with a letter and a digit"
          error={errors.password?.message}
          {...register('password')}
        />

        <Button type="submit" size="lg" loading={isSubmitting}>
          {isSubmitting ? 'Creating account…' : copy.cta}
        </Button>
      </form>

      <p className="mt-8 text-center text-sm text-fg-secondary">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-accent hover:underline">
          Log in
        </Link>
        <span className="mx-2 text-fg-muted">·</span>
        {role === 'RIDER' ? (
          <Link to="/register/driver" className="font-semibold text-accent hover:underline">
            Drive with us
          </Link>
        ) : (
          <Link to="/register" className="font-semibold text-accent hover:underline">
            Sign up to ride
          </Link>
        )}
      </p>
    </div>
  )
}
