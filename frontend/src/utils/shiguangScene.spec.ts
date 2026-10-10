import { describe, expect, it } from 'vitest'
import { categoryMatchesSpaceTypes, mapVenueScene } from './shiguangScene'

describe('shiguang scene mapping (S02-A9)', () => {
  it('maps real space types to explainable life categories', () => {
    expect(mapVenueScene(['BASKETBALL'])).toBe('move')
    expect(mapVenueScene(['BADMINTON'])).toBe('move')
    expect(mapVenueScene(['MEETING_ROOM'])).toBe('quiet')
    expect(mapVenueScene(['泳池'])).toBe('water')
  })

  it('falls back to the decorative together scene for unmapped types', () => {
    expect(mapVenueScene(['SOMETHING_NEW'])).toBe('together')
    expect(mapVenueScene(undefined)).toBe('together')
  })

  it('keeps category filtering honest: all matches everything, category needs the mapped scene', () => {
    expect(categoryMatchesSpaceTypes('all', ['MEETING_ROOM'])).toBe(true)
    expect(categoryMatchesSpaceTypes('quiet', ['MEETING_ROOM'])).toBe(true)
    expect(categoryMatchesSpaceTypes('move', ['MEETING_ROOM'])).toBe(false)
  })
})
