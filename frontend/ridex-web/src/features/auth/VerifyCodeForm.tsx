import { zodResolver } from '@hookform/resolvers/zod'
import { MailCheck } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { TextField } from '@/components/ui/text-field'
import { otpSchema, type OtpValues } from './schemas'

const RESEND_COOLDOWN_SECONDS = 30

/** The email the registration page passed along, if the person arrived from there. */
function emailFromRegistration(state: unknown): string | null {
  if (state && typeof state === 'object' && 'email' in state && typeof state.email === 'string') {
    return state.email
  }
  return null
}

/**
 * Verify with the 6-digit code from the email.
 *
 * Right after registering, the email is already known, so only the code is asked for. Opening this page
 * directly (for example from a bookmark) has no email, so then it is asked for too.
 */
export function VerifyCodeForm() {
  const knownEmail = emailFromRegistration(useLocation().state)
  const [done, setDone] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(RESEND_COOLDOWN_SECONDS)
  const [resending, setResending] = useState(false)
  const {
    register,
    handleSubmit,
    getValues,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<OtpValues>({
    resolver: zodResolver(otpSchema),
    mode: 'onTouched',
    defaultValues: { email: knownEmail ?? '', code: '' },
  })

  // A code was just emailed, so "send again" only unlocks after a short wait
  useEffect(() => {
    if (cooldown <= 0) return
    const timer = window.setTimeout(() => setCooldown((seconds) => seconds - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [cooldown])

  async function submit(values: OtpValues) {
    setFormError(null)
    try {
      await api.post('/api/auth/verify-otp', values)
      setDone(true)
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    }
  }

  async function resend() {
    if (!(await trigger('email'))) return
    setResending(true)
    try {
      await api.post('/api/auth/resend-verification', { email: getValues('email') })
      toast.success('A new code and link were sent')
      setCooldown(RESEND_COOLDOWN_SECONDS)
      setFormError(null)
    } catch (err) {
      setFormError(toFriendlyError(err).message)
    } finally {
      setResending(false)
    }
  }

  if (done) {
    return (
      <div>
        <span className="grid size-14 place-items-center rounded-lg bg-success/10 text-success">
          <MailCheck className="size-7" aria-hidden />
        </span>
        <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Email confirmed</h1>
        <p className="mt-2 text-fg-secondary">Your account is active. You can log in now.</p>
        <Button asChild size="lg" className="mt-8 w-full">
          <Link to="/login">Log in</Link>
        </Button>
      </div>
    )
  }

  return (
    <div>
      <span className="grid size-14 place-items-center rounded-lg bg-accent-subtle text-accent">
        <MailCheck className="size-7" aria-hidden />
      </span>
      <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Check your email</h1>
      <p className="mt-2 text-fg-secondary">
        {knownEmail ? (
          <>
            We sent a 6-digit code to <strong className="text-fg">{knownEmail}</strong>. Enter it below to activate your
            account. You can also open the link in the email instead.
          </>
        ) : (
          'Type your email and the 6-digit code from your verification email.'
        )}
      </p>
      <p className="mt-1 text-sm text-fg-muted">The code works once and expires after 10 minutes.</p>

      <form onSubmit={handleSubmit(submit)} noValidate className="mt-6 flex flex-col gap-5">
        {formError && <Alert kind="error">{formError}</Alert>}
        {!knownEmail && (
          <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        )}
        <TextField
          label="6-digit code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          autoFocus
          error={errors.code?.message}
          {...register('code')}
        />
        <Button type="submit" size="lg" loading={isSubmitting}>
          Verify
        </Button>
      </form>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-3 text-sm">
        <Button variant="ghost" size="sm" onClick={resend} loading={resending} disabled={cooldown > 0}>
          {cooldown > 0 ? `Send a new code in ${cooldown}s` : 'Send a new code'}
        </Button>
        <Link to="/login" className="text-fg-secondary hover:text-fg">
          Back to log in
        </Link>
      </div>
    </div>
  )
}
