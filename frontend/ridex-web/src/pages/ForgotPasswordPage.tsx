import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import { Alert } from '../components/Alert'
import { FormField } from '../components/FormField'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.post('/api/auth/forgot-password', { email })
      setSent(true)
    } catch (err) {
      setError(toFriendlyError(err).message)
    } finally {
      setBusy(false)
    }
  }

  if (sent) {
    return (
      <section className="card">
        <h1>Check your email</h1>
        {/* Same wording whether or not the address exists: we never reveal who has an account */}
        <Alert kind="info">If an account exists for {email}, a reset link is on its way.</Alert>
        <p>
          <Link to="/login">Back to log in</Link>
        </p>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>Forgot your password?</h1>
      {error && <Alert kind="error">{error}</Alert>}
      <form onSubmit={handleSubmit}>
        <FormField
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Sending…' : 'Send reset link'}
        </button>
      </form>
      <p className="muted">
        <Link to="/login">Back to log in</Link>
      </p>
    </section>
  )
}
