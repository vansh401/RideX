import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import { Alert } from '../components/Alert'

/**
 * The email link lands here. We deliberately wait for a click instead of verifying on load:
 * mail scanners and link previews open URLs automatically, and a plain page load must not
 * change anything (the same reason the API uses POST).
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

  if (!token) {
    return (
      <section className="card">
        <h1>Verify your email</h1>
        <Alert kind="error">This link is missing its token. Open the link from your email again.</Alert>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>Verify your email</h1>
      {state === 'done' ? (
        <>
          <Alert kind="success">Your email is confirmed. You can log in now.</Alert>
          <p>
            <Link to="/login">Go to log in</Link>
          </p>
        </>
      ) : (
        <>
          {error && <Alert kind="error">{error}</Alert>}
          <p>Confirm that this is your email address.</p>
          <button type="button" onClick={confirm} disabled={state === 'busy'}>
            {state === 'busy' ? 'Confirming…' : 'Confirm my email'}
          </button>
        </>
      )}
    </section>
  )
}
