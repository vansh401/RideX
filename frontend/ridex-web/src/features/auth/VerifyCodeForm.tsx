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
import { otpSchema, type OtpValues } from './schemas'

/** Verify with the 6-digit code from the email instead of the link (for another device, say). */
export function VerifyCodeForm() {
  const [done, setDone] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<OtpValues>({ resolver: zodResolver(otpSchema), mode: 'onTouched' })

  async function submit(values: OtpValues) {
    setFormError(null)
    try {
      await api.post('/api/auth/verify-otp', values)
      setDone(true)
    } catch (err) {
      setFormError(toFriendlyError(err).message)
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
      <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Enter your code</h1>
      <p className="mt-2 text-fg-secondary">
        Type the 6-digit code from your verification email. It expires after a few minutes.
      </p>
      <form onSubmit={handleSubmit(submit)} noValidate className="mt-6 flex flex-col gap-5">
        {formError && <Alert kind="error">{formError}</Alert>}
        <TextField label="Email" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField
          label="6-digit code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          error={errors.code?.message}
          {...register('code')}
        />
        <Button type="submit" size="lg" loading={isSubmitting}>
          Verify
        </Button>
      </form>
    </div>
  )
}
