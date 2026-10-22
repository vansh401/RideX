import type { LucideIcon } from 'lucide-react'
import { Card } from '@/components/ui/card'

/**
 * KPI tile from the references: icon chip, big number, caption. `preview` marks numbers that
 * are sample data because the backend feature does not exist yet.
 */
export function StatCard({
  icon: Icon,
  label,
  value,
  preview = false,
}: {
  icon: LucideIcon
  label: string
  value: string
  preview?: boolean
}) {
  return (
    <Card interactive className="p-5">
      <div className="flex items-center justify-between">
        <span className="grid size-10 place-items-center rounded-md bg-gradient-to-br from-accent/25 to-accent/5 text-accent ring-1 ring-accent/25">
          <Icon className="size-5" aria-hidden />
        </span>
        {preview && <span className="eyebrow">Preview data</span>}
      </div>
      <p className="tabular mt-4 text-3xl font-bold tracking-tight text-gradient">{value}</p>
      <p className="text-sm text-fg-muted">{label}</p>
    </Card>
  )
}
