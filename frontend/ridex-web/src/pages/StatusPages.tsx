import { Link } from 'react-router-dom'

export function ForbiddenPage() {
  return (
    <section className="card">
      <h1>Not allowed</h1>
      <p>Your account does not have access to that page.</p>
      <p>
        <Link to="/dashboard">Back to your home</Link>
      </p>
    </section>
  )
}

export function NotFoundPage() {
  return (
    <section className="card">
      <h1>Page not found</h1>
      <p>
        <Link to="/">Go home</Link>
      </p>
    </section>
  )
}
