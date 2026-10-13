import { zodResolver } from '@hookform/resolvers/zod'
import { MailCheck } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { TextField } from '@/components/ui/text-field'
import { forgotSchema, type ForgotValues } from './schemas'

export function ForgotPasswordPage() {
  const [formError, setFormError] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotValues>({ resolver: zodResolver(forgotSchema), mode: 'onTouched' })

  async function onSubmit(values: ForgotValues) {
    setFormError(null)
    try {
      await api.post('/api/auth/forgot-password', values)
      setSentTo(values.email)
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    }
  }

  if (sentTo) {
    return (
      <div>
        <span className="grid size-14 place-items-center rounded-lg bg-accent-subtle text-accent">
          <MailCheck className="size-7" aria-hidden />
        </span>
        <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Check your email</h1>
        {/* Same wording whether or not the address exists: we never reveal who has an account */}
        <p className="mt-2 text-fg-secondary">
          If an account exists for <strong className="text-fg">{sentTo}</strong>, a password reset link is on its way.
          It expires after 30 minutes.
        </p>
        <Button asChild variant="secondary" size="lg" className="mt-8 w-full">
          <Link to="/login">Back to log in</Link>
        </Button>
      </div>
    )
  }

  return (
    <div>
      <h1 className="text-3xl font-bold tracking-tight text-fg">Forgot your password?</h1>
      <p className="mt-2 text-fg-secondary">Enter your email and we will send you a link to choose a new one.</p>

      <form onSubmit={handleSubmit(onSubmit)} noValidate className="mt-8 flex flex-col gap-5">
        {formError && <Alert kind="error">{formError}</Alert>}
        <TextField
          label="Email"
          type="email"
          autoComplete="email"
          inputMode="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <Button type="submit" size="lg" loading={isSubmitting}>
          {isSubmitting ? 'Sending…' : 'Send reset link'}
        </Button>
      </form>

      <p className="mt-8 text-center text-sm text-fg-secondary">
        <Link to="/login" className="font-semibold text-accent hover:underline">
          Back to log in
        </Link>
      </p>
    </div>
  )
}
