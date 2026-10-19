import { Star } from 'lucide-react'
import { cn } from '@/lib/cn'

/** Choose 1 to 5 stars. A radio group, so it works with keyboard and screen readers. */
export function StarRating({ value, onChange, disabled = false }: { value: number; onChange: (stars: number) => void; disabled?: boolean }) {
  return (
    <div role="radiogroup" aria-label="Rating" className="flex gap-1">
      {[1, 2, 3, 4, 5].map((stars) => (
        <button
          key={stars}
          type="button"
          role="radio"
          aria-checked={value === stars}
          aria-label={`${stars} star${stars === 1 ? '' : 's'}`}
          disabled={disabled}
          onClick={() => onChange(stars)}
          className="grid size-11 place-items-center rounded-md hover:bg-elevated disabled:opacity-50"
        >
          <Star className={cn('size-7 transition-colors', stars <= value ? 'fill-accent text-accent' : 'text-fg-muted')} aria-hidden />
        </button>
      ))}
    </div>
  )
}

/** Read-only stars, e.g. "★ 4.8 (23)". */
export function RatingValue({ average, count }: { average: number | null; count: number }) {
  if (average === null || count === 0) return <span className="text-sm text-fg-muted">New driver</span>
  return (
    <span className="inline-flex items-center gap-1 text-sm text-fg">
      <Star className="size-4 fill-accent text-accent" aria-hidden />
      <span className="tabular font-semibold">{average.toFixed(1)}</span>
      <span className="text-fg-muted">({count})</span>
    </span>
  )
}
