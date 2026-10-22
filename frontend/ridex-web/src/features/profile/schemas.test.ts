import { describe, expect, it } from 'vitest'
import { emergencyContactSchema, licenceSchema, savedPlaceSchema, todayIso } from './schemas'

// These rules must stay in step with the backend (SavedPlaceRequest, UpdateRiderProfileRequest,
// DriverProfileRequest).

const place = { label: 'Home', address: '22 Windsor Lane', latitude: '12.971599', longitude: '77.594566' }

describe('savedPlaceSchema', () => {
  it('accepts a normal place', () => {
    expect(savedPlaceSchema.safeParse(place).success).toBe(true)
  })

  it('accepts coordinates at the edges of the range', () => {
    expect(savedPlaceSchema.safeParse({ ...place, latitude: '90', longitude: '-180' }).success).toBe(true)
  })

  it.each([
    ['latitude beyond 90', { latitude: '90.5' }],
    ['latitude beyond -90', { latitude: '-91' }],
    ['longitude beyond 180', { longitude: '180.1' }],
    ['more than six decimals', { latitude: '12.1234567' }],
    ['text instead of a number', { latitude: 'north' }],
    ['an empty coordinate', { longitude: '' }],
  ])('rejects %s', (_label, change) => {
    expect(savedPlaceSchema.safeParse({ ...place, ...change }).success).toBe(false)
  })

  it('limits the label and address length and requires both', () => {
    expect(savedPlaceSchema.safeParse({ ...place, label: 'L'.repeat(31) }).success).toBe(false)
    expect(savedPlaceSchema.safeParse({ ...place, address: 'A'.repeat(256) }).success).toBe(false)
    expect(savedPlaceSchema.safeParse({ ...place, label: '  ' }).success).toBe(false)
    expect(savedPlaceSchema.safeParse({ ...place, address: '' }).success).toBe(false)
  })
})

describe('emergencyContactSchema', () => {
  it('accepts a name with a phone', () => {
    expect(emergencyContactSchema.safeParse({ name: 'Meera Nair', phone: '+919811122233' }).success).toBe(true)
  })

  it('accepts both empty, which means "remove the contact"', () => {
    expect(emergencyContactSchema.safeParse({ name: '', phone: '' }).success).toBe(true)
  })

  it('needs both fields or neither', () => {
    const onlyName = emergencyContactSchema.safeParse({ name: 'Meera', phone: '' })
    expect(onlyName.success).toBe(false)
    expect(onlyName.error?.issues[0]?.path).toEqual(['phone'])

    const onlyPhone = emergencyContactSchema.safeParse({ name: '', phone: '+919811122233' })
    expect(onlyPhone.success).toBe(false)
    expect(onlyPhone.error?.issues[0]?.path).toEqual(['name'])
  })

  it('rejects something that is not a phone number', () => {
    expect(emergencyContactSchema.safeParse({ name: 'Meera', phone: '12345' }).success).toBe(false)
  })

  it('takes an optional email for SOS alerts and checks its shape', () => {
    const ok = { name: 'Meera Nair', phone: '+919811122233', email: 'meera@example.com' }
    expect(emergencyContactSchema.safeParse(ok).success).toBe(true)
    expect(emergencyContactSchema.safeParse({ ...ok, email: '' }).success).toBe(true)
    const bad = emergencyContactSchema.safeParse({ ...ok, email: 'not-an-email' })
    expect(bad.success).toBe(false)
    expect(bad.error?.issues[0]?.path).toEqual(['email'])
  })

  it('an email alone is not a contact: it needs a name', () => {
    const result = emergencyContactSchema.safeParse({ name: '', phone: '', email: 'meera@example.com' })
    expect(result.success).toBe(false)
    expect(result.error?.issues.some((i) => i.path[0] === 'name')).toBe(true)
  })
})

describe('licenceSchema', () => {
  // The schema compares against the LOCAL date (todayIso). Building "tomorrow" from toISOString() would use the UTC
  // date, which is still "today" for several hours each night in timezones ahead of UTC (such as India).
  const tomorrow = () => {
    const d = new Date()
    d.setDate(d.getDate() + 1)
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${d.getFullYear()}-${month}-${day}`
  }

  it('accepts a valid licence expiring in the future', () => {
    expect(licenceSchema.safeParse({ licenseNumber: 'DL-0420110012345', licenseExpiry: tomorrow() }).success).toBe(true)
  })

  it('rejects an expiry that is today or earlier', () => {
    expect(licenceSchema.safeParse({ licenseNumber: 'DL-12345', licenseExpiry: todayIso() }).success).toBe(false)
    expect(licenceSchema.safeParse({ licenseNumber: 'DL-12345', licenseExpiry: '2020-01-01' }).success).toBe(false)
  })

  it.each(['abc', 'has_underscore_x', 'x'.repeat(31)])('rejects the licence number "%s"', (licenseNumber) => {
    expect(licenceSchema.safeParse({ licenseNumber, licenseExpiry: tomorrow() }).success).toBe(false)
  })
})

describe('licenceSchema whitespace', () => {
  it('trims the number before checking it (the trimmed value is what gets submitted)', () => {
    const result = licenceSchema.safeParse({ licenseNumber: '  DL-12345  ', licenseExpiry: '2999-01-01' })
    expect(result.success).toBe(true)
    expect(result.data?.licenseNumber).toBe('DL-12345')
  })
})
