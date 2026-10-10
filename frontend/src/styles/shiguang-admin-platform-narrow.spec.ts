import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 平台面窄屏溢出修复的合同回归（SHIGUANG-WAVE-03-REMEDIATION-20260917 S03 / F-16）。
 *
 * 第三波实测 /system-admin/users 在 320px 文档级横向溢出 30px；升级几何探针证明根因是
 * 账号列表 .person 内不可断行的长用户名（如 W4S04_reg_absent_10db2c）以墨迹外溢逐级
 * 传播到文档（同一 .domain-nav 在 roles/logs 页签均无溢出，排除导航归因）。
 * 这里锁定三件事不被再次引入：
 * 1. 长词断行规则（overflow-wrap:anywhere）保持在 .person strong/small 上；
 * 2. 移动端领域导航保持自身局部横向滚动（overflow:auto），不许改成隐藏/裁切；
 * 3. 不得出现 body/html 级 overflow-x:hidden 这类掩盖式修复。
 */
const readFrom = (rel: string): string => {
  for (const base of [process.cwd(), resolve(process.cwd(), 'frontend')]) {
    try {
      return readFileSync(resolve(base, rel), 'utf8')
    } catch {
      // 尝试下一个候选根（vitest 可从仓库根或 frontend 目录启动）
    }
  }
  throw new Error(`cannot locate ${rel}`)
}

const css = readFrom('src/styles/shiguang-admin-platform.css')

const mobileBlock = css.match(/@media\(max-width:760px\)\{([\s\S]*?)\}\n\n?@media\(prefers-reduced-motion/)?.[1] ?? ''

describe('平台面窄屏溢出修复合同（F-16）', () => {
  it('账号列表的长用户名/角色词允许断行，不再以墨迹外溢撑破文档', () => {
    expect(css).toMatch(/\.sga-c \.person strong,\.sga-c \.person small\{overflow-wrap:anywhere\}/)
  })

  it('移动端领域导航保持局部横向滚动而不是隐藏溢出', () => {
    expect(mobileBlock).toContain('.sga-c .domain-nav{flex-direction:row;overflow:auto')
    expect(mobileBlock).toContain('.sga-c .domain-nav button{white-space:nowrap;min-width:104px}')
  })

  it('不引入 body/html 级 overflow-x:hidden 掩盖式修复', () => {
    expect(css).not.toMatch(/(^|[}\s,])(html|body)[^{]*\{[^}]*overflow-x:\s*hidden/)
  })
})
