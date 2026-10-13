import { Compass, Lock } from 'lucide-react'
import { Link } from 'react-router-dom'
import { EmptyState } from '@/components/brand/EmptyState'
import { Button } from '@/components/ui/button'

export function ForbiddenPage() {
  return (
    <EmptyState
      icon={Lock}
      title="You do not have access to that page"
      description="The role on your account does not include this area."
      action={
        <Button asChild variant="secondary">
          <Link to="/dashboard">Back to your home</Link>
        </Button>
      }
    />
  )
}

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-xl px-4 pb-24 pt-40">
      <EmptyState
        icon={Compass}
        title="Page not found"
        description="That address does not exist."
        action={
          <Button asChild>
            <Link to="/">Go to the home page</Link>
          </Button>
        }
      />
    </div>
  )
}
