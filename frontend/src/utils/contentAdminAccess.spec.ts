import { describe, expect, it } from 'vitest'
import { canAccessContentAdmin } from './contentAdminAccess'

describe('content admin workspace access', () => {
  it('requires the independent entry permission instead of accepting a leaf action permission', () => {
    expect(canAccessContentAdmin(['community:content:moderate'])).toBe(false)
    expect(canAccessContentAdmin(['content-admin:view', 'community:content:moderate'])).toBe(true)
  })
})
