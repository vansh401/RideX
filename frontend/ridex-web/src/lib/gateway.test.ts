import { describe, expect, it } from 'vitest'
import { CHAOS_MODES, CIRCUIT_LABEL, CIRCUIT_TONE, formatFailureRate } from './gateway'

describe('formatFailureRate', () => {
  it('says so when there are not enough calls to compute a rate', () => {
    expect(formatFailureRate(null)).toBe('not enough calls yet')
    expect(formatFailureRate(-1)).toBe('not enough calls yet')
  })

  it('rounds a real rate to a whole percent', () => {
    expect(formatFailureRate(0)).toBe('0%')
    expect(formatFailureRate(66.666)).toBe('67%')
  })
})

describe('circuit states', () => {
  it('has a label and a tone for every state the backend can report', () => {
    for (const state of ['CLOSED', 'OPEN', 'HALF_OPEN', 'DISABLED', 'FORCED_OPEN', 'METRICS_ONLY'] as const) {
      expect(CIRCUIT_LABEL[state]).toBeTruthy()
      expect(CIRCUIT_TONE[state]).toBeTruthy()
    }
  })

  it('shows an open circuit as a danger and a closed one as healthy', () => {
    expect(CIRCUIT_TONE.OPEN).toBe('danger')
    expect(CIRCUIT_TONE.CLOSED).toBe('success')
  })
})

describe('chaos modes', () => {
  it('offers the four ways the test gateway can misbehave, with a delay only where it matters', () => {
    expect(CHAOS_MODES.map((m) => m.mode)).toEqual(['NORMAL', 'ERROR', 'SLOW', 'SLOW_AFTER_PROCESSING'])
    expect(CHAOS_MODES.filter((m) => m.delayMs > 0).map((m) => m.mode)).toEqual(['SLOW', 'SLOW_AFTER_PROCESSING'])
  })
})
