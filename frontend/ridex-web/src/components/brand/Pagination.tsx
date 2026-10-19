import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'

/** Previous / next with a plain-language "Showing 11-20 of 134". */
export function Pagination({
  page,
  size,
  totalElements,
  totalPages,
  onPage,
}: {
  page: number
  size: number
  totalElements: number
  totalPages: number
  onPage: (page: number) => void
}) {
  if (totalElements === 0) return null
  const from = page * size + 1
  const to = Math.min((page + 1) * size, totalElements)
  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center justify-between gap-3 pt-4">
      <p className="tabular text-sm text-fg-muted">
        Showing {from}-{to} of {totalElements}
      </p>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          <ChevronLeft className="size-4" aria-hidden /> Previous
        </Button>
        <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next <ChevronRight className="size-4" aria-hidden />
        </Button>
      </div>
    </nav>
  )
}
