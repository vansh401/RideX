import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import { Alert } from '../components/Alert'
import { FormField } from '../components/FormField'

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')

  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setFieldErrors({})
    if (password !== confirm) {
      setFieldErrors({ confirm: 'Passwords do not match' })
      return
    }
    setBusy(true)
    try {
      await api.post('/api/auth/reset-password', { token, newPassword: password })
      setDone(true)
    } catch (err) {
      const friendly = toFriendlyError(err)
      setError(friendly.message)
      setFieldErrors({ newPassword: friendly.fieldErrors.newPassword ?? '' })
    } finally {
      setBusy(false)
    }
  }

  if (!token) {
    return (
      <section className="card">
        <h1>Reset your password</h1>
        <Alert kind="error">This link is missing its token.</Alert>
        <p>
          <Link to="/forgot-password">Request a new link</Link>
        </p>
      </section>
    )
  }

  if (done) {
    return (
      <section className="card">
        <h1>Password changed</h1>
        <Alert kind="success">Your password was updated and other devices were logged out.</Alert>
        <p>
          <Link to="/login">Log in with your new password</Link>
        </p>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>Choose a new password</h1>
      {error && <Alert kind="error">{error}</Alert>}
      <form onSubmit={handleSubmit}>
        <FormField
          label="New password (8+ characters, a letter and a digit)"
          type="password"
          autoComplete="new-password"
          required
          value={password}
          error={fieldErrors.newPassword || undefined}
          onChange={(e) => setPassword(e.target.value)}
        />
        <FormField
          label="Repeat the new password"
          type="password"
          autoComplete="new-password"
          required
          value={confirm}
          error={fieldErrors.confirm}
          onChange={(e) => setConfirm(e.target.value)}
        />
        <button type="submit" disabled={busy}>
          {busy ? 'Saving…' : 'Change password'}
        </button>
      </form>
    </section>
  )
}
