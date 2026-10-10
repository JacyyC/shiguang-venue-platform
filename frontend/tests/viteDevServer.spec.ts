// @vitest-environment node

import config from '../vite.config'
import { describe, expect, it } from 'vitest'

describe('vite dev server config', () => {
  it('fails fast instead of moving to another port when 5173 is occupied', () => {
    const resolved = typeof config === 'function' ? config({ command: 'serve', mode: 'test' }) : config

    expect(resolved.server?.port).toBe(5173)
    expect(resolved.server?.strictPort).toBe(true)
  })
})
