import { formatINR } from '@/lib/format'

interface Fare {
  baseFare: number
  distanceFare: number
  timeFare: number
  surgeMultiplier: number
  subtotal: number
  tax: number
  total: number
}

/** The price, line by line, so nothing about the total is a mystery. */
export function FareBreakdown({ fare, minimumFareApplied = false }: { fare: Fare; minimumFareApplied?: boolean }) {
  return (
    <dl className="tabular grid grid-cols-[1fr_auto] gap-x-6 gap-y-1.5 text-sm">
      <dt className="text-fg-secondary">Base fare</dt>
      <dd className="text-right text-fg">{formatINR(fare.baseFare)}</dd>
      <dt className="text-fg-secondary">Distance</dt>
      <dd className="text-right text-fg">{formatINR(fare.distanceFare)}</dd>
      <dt className="text-fg-secondary">Time</dt>
      <dd className="text-right text-fg">{formatINR(fare.timeFare)}</dd>
      {minimumFareApplied && (
        <>
          <dt className="text-fg-secondary">Minimum fare applied</dt>
          <dd className="text-right text-fg-muted">to {formatINR(fare.subtotal)}</dd>
        </>
      )}
      {fare.surgeMultiplier !== 1 && (
        <>
          <dt className="text-fg-secondary">Surge</dt>
          <dd className="text-right text-fg">x{fare.surgeMultiplier}</dd>
        </>
      )}
      <dt className="text-fg-secondary">Tax</dt>
      <dd className="text-right text-fg">{formatINR(fare.tax)}</dd>
      <dt className="mt-1 border-t border-border pt-2 font-semibold text-fg">Total</dt>
      <dd className="mt-1 border-t border-border pt-2 text-right text-lg font-bold text-fg">{formatINR(fare.total)}</dd>
    </dl>
  )
}
