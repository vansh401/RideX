import { Trophy } from 'lucide-react'
import { api } from '@/api/client'
import type { IncentiveProgress } from '@/api/types'
import { Card, CardContent } from '@/components/ui/card'
import { formatINR } from '@/lib/format'
import { usePolling } from '@/lib/usePolling'

/** Today's trips against the bonus tiers: a bar toward the next tier, and what is already guaranteed. Bonuses are paid after midnight. */
export function IncentiveCard() {
  const { data } = usePolling(async () => (await api.get<IncentiveProgress>('/api/driver/incentives')).data, { intervalMs: 30000 })
  if (!data || data.tiers.length === 0) return null
  const target = data.next?.trips ?? data.tiers[data.tiers.length - 1].trips
  const percent = Math.min(100, Math.round((data.tripsToday / target) * 100))

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <p className="flex items-center gap-2 font-semibold text-fg">
          <Trophy className="size-4 text-accent" aria-hidden /> Today&apos;s bonus
        </p>
        <div
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={target}
          aria-valuenow={Math.min(data.tripsToday, target)}
          aria-label="Trips toward the next bonus"
          className="h-2.5 overflow-hidden rounded-full bg-white/10"
        >
          <div className="h-full rounded-full bg-gradient-to-r from-accent to-accent-hover transition-[width] duration-(--dur-slow)" style={{ width: `${percent}%` }} />
        </div>
        <p className="text-sm text-fg-secondary">
          {data.next ? (
            <>
              <span className="font-semibold text-fg">{data.tripsToday}</span> trips today. {data.tripsToNext} more for a{' '}
              <span className="font-semibold text-fg">{formatINR(data.next.bonus)}</span> bonus.
            </>
          ) : (
            <>
              <span className="font-semibold text-fg">{data.tripsToday}</span> trips today: you reached the top tier.
            </>
          )}
        </p>
        {data.bonusSoFar > 0 && <p className="text-xs text-success">{formatINR(data.bonusSoFar)} bonus earned so far today, paid after midnight.</p>}
      </CardContent>
    </Card>
  )
}
