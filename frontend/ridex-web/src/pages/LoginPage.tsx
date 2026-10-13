import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import { Alert } from '../components/Alert'
import { FormField } from '../components/FormField'
import { useAuth } from '../auth/useAuth'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [needsVerification, setNeedsVerification] = useState(false)
  const [resent, setResent] = useState(false)
  const [busy, setBusy] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setNeedsVerification(false)
    try {
      await login(email, password)
      navigate(from, { replace: true })
    } catch (err) {
      const friendly = toFriendlyError(err)
      setError(friendly.message)
      setNeedsVerification(friendly.status === 403 && /verify/i.test(friendly.message))
    } finally {
      setBusy(false)
    }
  }

  async function resendVerification() {
    try {
      await api.post('/api/auth/resend-verification', { email })
      setResent(true)
    } catch (err) {
      setError(toFriendlyError(err).message)
    }
  }

  return (
    <section className="card">
      <h1>Log in</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {needsVerification &&
        (resent ? (
          <Alert kind="success">A new verification email is on its way.</Alert>
        ) : (
          <p>
            <button type="button" className="link-button" onClick={resendVerification}>
              Resend verification email
            </button>
          </p>
        ))}
      <form onSubmit={handleSubmit}>
        <FormField
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <FormField
          label="Password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <p className="muted">
        <Link to="/forgot-password">Forgot your password?</Link> · <Link to="/register">Create an account</Link>
      </p>
    </section>
  )
}
