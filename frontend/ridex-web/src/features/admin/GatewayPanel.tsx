import { ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { toFriendlyError } from '@/api/errors'
import { api } from '@/api/client'
import type { GatewayStatus } from '@/api/types'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { CHAOS_MODES, CIRCUIT_LABEL, CIRCUIT_TONE, formatFailureRate, type ChaosMode } from '@/lib/gateway'
import { usePolling } from '@/lib/usePolling'

/**
 * Makes the protections around the payment gateway visible. The gateway here is a test double, so an admin can break it on
 * purpose (errors, hangs, answers that arrive too late) and watch the retry, timeout, circuit breaker and bulkhead react.
 */
export function GatewayPanel() {
  const status = usePolling(async () => (await api.get<GatewayStatus>('/api/admin/payments/gateway')).data, { intervalMs: 2000 })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await status.refresh()
    } catch (e) {
      setError(toFriendlyError(e).message)
    } finally {
      setBusy(false)
    }
  }

  const gateway = status.data
  const current = CHAOS_MODES.find((m) => m.mode === gateway?.chaos.mode)

  return (
    <Card className="mb-6">
      <CardContent>
        <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
          <p className="flex items-center gap-2 font-semibold text-fg">
            <ShieldCheck className="size-4" aria-hidden /> Payment gateway protections
          </p>
          {gateway && <Badge tone={CIRCUIT_TONE[gateway.circuitState]}>{CIRCUIT_LABEL[gateway.circuitState]}</Badge>}
        </div>
        <p className="mb-3 text-sm text-fg-muted">
          Every charge goes to the gateway over the network, protected by a timeout, retries, a circuit breaker and a bulkhead. Make the
          test gateway misbehave, then complete a ride and watch what happens to its payment.
        </p>
        {(status.error || error) && <Alert kind="error" className="mb-3">{error ?? status.error}</Alert>}

        {gateway && (
          <dl className="mb-4 grid gap-2 text-sm sm:grid-cols-4">
            <div>
              <dt className="text-fg-muted">Recent failure rate</dt>
              <dd className="tabular font-semibold text-fg">{formatFailureRate(gateway.failureRatePercent)}</dd>
            </div>
            <div>
              <dt className="text-fg-muted">Calls refused by the circuit</dt>
              <dd className="tabular font-semibold text-fg">{gateway.callsRefusedByCircuit}</dd>
            </div>
            <div>
              <dt className="text-fg-muted">Free slots (bulkhead)</dt>
              <dd className="tabular font-semibold text-fg">
                {gateway.bulkheadAvailableCalls} of {gateway.bulkheadMaxCalls}
              </dd>
            </div>
            <div>
              <dt className="text-fg-muted">Calls the gateway received</dt>
              <dd className="tabular font-semibold text-fg">{gateway.callsReceivedByGateway}</dd>
            </div>
          </dl>
        )}

        <p className="mb-2 text-sm font-semibold text-fg">How the test gateway behaves now</p>
        <div className="flex flex-wrap gap-2" role="group" aria-label="Test gateway behaviour">
          {CHAOS_MODES.map((m) => (
            <Button
              key={m.mode}
              size="sm"
              variant={gateway?.chaos.mode === m.mode ? 'primary' : 'secondary'}
              disabled={busy}
              onClick={() => run(() => api.post('/api/admin/payments/gateway/chaos', { mode: m.mode as ChaosMode, delayMs: m.delayMs }))}
            >
              {m.label}
            </Button>
          ))}
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => run(() => api.post('/api/admin/payments/gateway/circuit-reset'))}>
            Close the circuit
          </Button>
        </div>
        {current && <p className="mt-2 text-sm text-fg-muted">{current.hint}</p>}
      </CardContent>
    </Card>
  )
}
