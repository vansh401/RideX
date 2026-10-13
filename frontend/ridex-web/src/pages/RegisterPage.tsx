import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { toFriendlyError } from '../api/errors'
import { Alert } from '../components/Alert'
import { FormField } from '../components/FormField'

export function RegisterPage() {
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    phone: '',
    password: '',
    role: 'RIDER' as 'RIDER' | 'DRIVER',
  })
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)

  function update<K extends keyof typeof form>(key: K, value: (typeof form)[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    setFieldErrors({})
    try {
      await api.post('/api/auth/register', form)
      setDone(true)
    } catch (err) {
      const friendly = toFriendlyError(err)
      setError(friendly.message)
      setFieldErrors(friendly.fieldErrors)
    } finally {
      setBusy(false)
    }
  }

  if (done) {
    return (
      <section className="card">
        <h1>Check your email</h1>
        <Alert kind="success">
          We sent a verification link to <strong>{form.email}</strong>. Open it to activate your account,
          then log in.
        </Alert>
        <p>
          <Link to="/login">Go to log in</Link>
        </p>
      </section>
    )
  }

  return (
    <section className="card">
      <h1>Create your account</h1>
      {error && <Alert kind="error">{error}</Alert>}
      <form onSubmit={handleSubmit}>
        <FormField
          label="Full name"
          autoComplete="name"
          required
          value={form.fullName}
          error={fieldErrors.fullName}
          onChange={(e) => update('fullName', e.target.value)}
        />
        <FormField
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={form.email}
          error={fieldErrors.email}
          onChange={(e) => update('email', e.target.value)}
        />
        <FormField
          label="Phone (10-15 digits, optional +)"
          type="tel"
          autoComplete="tel"
          required
          value={form.phone}
          error={fieldErrors.phone}
          onChange={(e) => update('phone', e.target.value)}
        />
        <FormField
          label="Password (8+ characters, a letter and a digit)"
          type="password"
          autoComplete="new-password"
          required
          value={form.password}
          error={fieldErrors.password}
          onChange={(e) => update('password', e.target.value)}
        />
        <div className="field">
          <label htmlFor="role">I want to</label>
          <select id="role" value={form.role} onChange={(e) => update('role', e.target.value as 'RIDER' | 'DRIVER')}>
            <option value="RIDER">Ride with RideX</option>
            <option value="DRIVER">Drive with RideX</option>
          </select>
          {fieldErrors.role && <span className="field-error">{fieldErrors.role}</span>}
        </div>
        <button type="submit" disabled={busy}>
          {busy ? 'Creating…' : 'Sign up'}
        </button>
      </form>
      <p className="muted">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </section>
  )
}
