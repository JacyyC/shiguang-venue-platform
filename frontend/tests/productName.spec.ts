// @vitest-environment node

import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'
import { describe, expect, it } from 'vitest'

describe('产品名称', () => {
  it('在浏览器标题中显示正式产品名称', () => {
    const indexHtml = readFileSync(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8')

    expect(indexHtml).toContain('<title>场地空间与活动平台</title>')
  })
})
