import { describe, expect, it } from 'vitest'
import { backoffDelay, tokenExpiryMs } from './RealtimeClient'

describe('backoffDelay', () => {
  const noJitter = () => 0
  const fullJitter = () => 1

  it('doubles after every failed attempt', () => {
    expect([0, 1, 2, 3, 4].map((attempt) => backoffDelay(attempt, noJitter))).toEqual([1000, 2000, 4000, 8000, 16000])
  })

  it('never waits more than 30 seconds (plus jitter), however many attempts failed', () => {
    expect(backoffDelay(5, noJitter)).toBe(30_000)
    expect(backoffDelay(50, noJitter)).toBe(30_000)
    expect(backoffDelay(50, fullJitter)).toBe(37_500)
  })

  it('adds up to 25% random jitter so browsers do not all return at the same second', () => {
    expect(backoffDelay(0, fullJitter)).toBe(1250)
    expect(backoffDelay(2, () => 0.5)).toBe(4500)
  })

  it('treats a negative attempt as the first one', () => {
    expect(backoffDelay(-3, noJitter)).toBe(1000)
  })
})

describe('tokenExpiryMs', () => {
  // header.payload.signature, where the payload is base64url JSON. Only the payload is read.
  const jwt = (payload: object) => `xx.${btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}.yy`

  it('reads the exp claim and converts seconds to milliseconds', () => {
    expect(tokenExpiryMs(jwt({ exp: 1_800_000_000 }))).toBe(1_800_000_000_000)
  })

  it('returns null for a token without exp, or one that is not a token', () => {
    expect(tokenExpiryMs(jwt({ sub: 'x' }))).toBeNull()
    expect(tokenExpiryMs('not-a-jwt')).toBeNull()
    expect(tokenExpiryMs('a.%%%.c')).toBeNull()
    expect(tokenExpiryMs('')).toBeNull()
  })
})
