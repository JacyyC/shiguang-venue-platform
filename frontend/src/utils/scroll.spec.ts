import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { scrollToPageHead } from './scroll'

const readView = (name: string) => readFileSync(resolve(process.cwd(), `src/views/user/${name}`), 'utf8')

describe('scrollToPageHead', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('uses smooth scrolling by default and auto scrolling for reduced motion', () => {
    const head = document.createElement('div')
    head.className = 'page-head'
    document.body.append(head)
    const scrollIntoView = vi.fn()
    head.scrollIntoView = scrollIntoView
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false }))

    scrollToPageHead()
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' })

    vi.mocked(window.matchMedia).mockReturnValue({ matches: true } as MediaQueryList)
    scrollToPageHead()
    expect(scrollIntoView).toHaveBeenLastCalledWith({ behavior: 'auto', block: 'start' })
  })

  it('is wired to every S08 pagination instance', () => {
    // 会员中心三入口已由 SHIGUANG-MEMBER-REFACTOR-20260912 S04 替换为拾光页面
    //（查询/游标导航，非 S08 分页形态），相应行随旧视图一并退役。
    // 交易面三入口（S04）与个人面五入口（S05）已由 SHIGUANG-BATCH-01-REFACTOR-20260913
    // 替换为拾光面（hash 区块导航，非 S08 分页形态），相应行随旧视图一并退役。
    const cases = [
      ['VenueListView.vue', 1]
    ] as const
    for (const [name, expected] of cases) {
      const source = readView(name)
      expect(source.match(/\.then\(scrollToPageHead\)/g)?.length, name).toBe(expected)
    }
  })
})
