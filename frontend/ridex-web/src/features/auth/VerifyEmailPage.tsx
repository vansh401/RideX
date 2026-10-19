import { MailCheck } from 'lucide-react'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { VerifyCodeForm } from './VerifyCodeForm'

/**
 * The email link lands here. Verification waits for a click on purpose: mail scanners and link
 * previews open URLs automatically, and merely loading a page must not change anything.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [state, setState] = useState<'idle' | 'busy' | 'done'>('idle')
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    if (!token) return
    setState('busy')
    setError(null)
    try {
      await api.post('/api/auth/verify-email', { token })
      setState('done')
    } catch (err) {
      setError(toFriendlyError(err).message)
      setState('idle')
    }
  }

  // No token in the address: this page was opened directly, so offer the 6-digit code instead
  if (!token) {
    return <VerifyCodeForm />
  }

  if (state === 'done') {
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
      <h1 className="mt-6 text-3xl font-bold tracking-tight text-fg">Confirm your email</h1>
      <p className="mt-2 text-fg-secondary">One last step: confirm that this is your email address.</p>
      {error && <Alert kind="error" className="mt-6">{error}</Alert>}
      <Button size="lg" className="mt-8 w-full" onClick={confirm} loading={state === 'busy'}>
        {state === 'busy' ? 'Confirming…' : 'Confirm my email'}
      </Button>
    </div>
  )
}
