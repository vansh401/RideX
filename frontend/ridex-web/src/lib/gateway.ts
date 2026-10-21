import type { GatewayStatus } from '@/api/types'
import type { Tone } from '@/lib/rides'

export type CircuitState = GatewayStatus['circuitState']

/** What each state of the circuit breaker means, in words an operator can act on. */
export const CIRCUIT_LABEL: Record<CircuitState, string> = {
  CLOSED: 'Closed: calls flow',
  OPEN: 'Open: calls are refused without trying',
  HALF_OPEN: 'Half open: trying a few calls',
  DISABLED: 'Disabled',
  FORCED_OPEN: 'Forced open',
  METRICS_ONLY: 'Metrics only',
}

export const CIRCUIT_TONE: Record<CircuitState, Tone> = {
  CLOSED: 'success',
  HALF_OPEN: 'warning',
  OPEN: 'danger',
  DISABLED: 'neutral',
  FORCED_OPEN: 'danger',
  METRICS_ONLY: 'neutral',
}

export type ChaosMode = GatewayStatus['chaos']['mode']

export const CHAOS_MODES: { mode: ChaosMode; label: string; hint: string; delayMs: number }[] = [
  { mode: 'NORMAL', label: 'Works normally', hint: 'The gateway answers every charge.', delayMs: 0 },
  { mode: 'ERROR', label: 'Returns errors', hint: 'Every call fails with HTTP 500: retries, then the circuit opens.', delayMs: 0 },
  { mode: 'SLOW', label: 'Hangs', hint: 'Answers after 5 seconds, longer than our timeout: nothing is charged.', delayMs: 5000 },
  {
    mode: 'SLOW_AFTER_PROCESSING',
    label: 'Charges, then answers too late',
    hint: 'The money moved but we time out: the payment waits and is asked again with the SAME key, so nobody is charged twice.',
    delayMs: 5000,
  },
]

/** A failure rate comes back as -1 until enough calls have been made to compute one. */
export function formatFailureRate(rate: number | null): string {
  return rate === null || rate < 0 ? 'not enough calls yet' : `${Math.round(rate)}%`
}
