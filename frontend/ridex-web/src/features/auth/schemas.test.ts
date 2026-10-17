import { describe, expect, it } from 'vitest'
import { loginSchema, registerSchema, resetSchema } from './schemas'

// These rules must stay in step with the backend (RegisterRequest / ResetPasswordRequest).

const validRegistration = {
  fullName: 'Asha Rao',
  email: 'asha@example.com',
  phone: '+919876543210',
  password: 'Secret123',
}

describe('registerSchema', () => {
  it('accepts a valid registration', () => {
    expect(registerSchema.safeParse(validRegistration).success).toBe(true)
  })

  it.each([
    ['short', 'Ab1'],
    ['letters only', 'onlyletters'],
    ['digits only', '12345678'],
    ['over 72 characters', 'a1'.repeat(37)],
  ])('rejects a password that is %s', (_label, password) => {
    expect(registerSchema.safeParse({ ...validRegistration, password }).success).toBe(false)
  })

  it('accepts a password of exactly 8 and exactly 72 characters', () => {
    expect(registerSchema.safeParse({ ...validRegistration, password: 'abcdefg1' }).success).toBe(true)
    expect(registerSchema.safeParse({ ...validRegistration, password: 'a1'.repeat(36) }).success).toBe(true)
  })

  it.each(['12345', 'abcdefghij', '+1234567890123456', '98-7654-3210'])('rejects the phone %s', (phone) => {
    expect(registerSchema.safeParse({ ...validRegistration, phone }).success).toBe(false)
  })

  it('rejects an invalid email and a blank name', () => {
    expect(registerSchema.safeParse({ ...validRegistration, email: 'not-an-email' }).success).toBe(false)
    expect(registerSchema.safeParse({ ...validRegistration, fullName: '   ' }).success).toBe(false)
  })
})

describe('resetSchema', () => {
  it('requires both passwords to match', () => {
    const result = resetSchema.safeParse({ password: 'Secret123', confirm: 'Secret124' })
    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['confirm'])
  })

  it('accepts matching, policy-compliant passwords', () => {
    expect(resetSchema.safeParse({ password: 'Secret123', confirm: 'Secret123' }).success).toBe(true)
  })
})

describe('loginSchema', () => {
  it('only asks for a valid email and some password (the server decides if it is right)', () => {
    expect(loginSchema.safeParse({ email: 'asha@example.com', password: 'x' }).success).toBe(true)
    expect(loginSchema.safeParse({ email: 'asha@example.com', password: '' }).success).toBe(false)
  })
})
