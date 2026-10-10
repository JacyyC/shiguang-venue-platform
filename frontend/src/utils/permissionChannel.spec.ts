import { readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 权限判定通道合同（SHIGUANG-BATCH-04-REFACTOR-20260914 S03 / B4-P1）。
 *
 * 产品里有两条权限通道，语义不同，不能互换：
 *
 * 1. **菜单树** `menu.hasPermission(perm)` —— 只遍历 `GET` 菜单接口返回的树，而该接口按
 *    `visible='Y'` 过滤（`be/system/mapper/SysMenuMapper`）。它回答的是「这个入口在导航里可见吗」。
 * 2. **权限快照** `auth.hasPermission(perm)` —— 来自 `/users/me/authorization` 的权限集合
 *    （`be/user/mapper/SysUserMapper.findPermissions`），**不过滤 visible**。它回答的是「我持有这个权限点吗」。
 *
 * 承载叶子能力的菜单节点是 `visible='N'` 的 `BUTTON`，永远不进菜单树。因此对叶子权限点调用
 * `menu.hasPermission` 在真实环境恒为 false。S02 对账发现的实际故障：`PromotionsView` 用菜单树判定
 * `coupon:manage`/`pricing:manage`，导致「优惠券模板 / 定价规则 / 核销记录」三块 UI 永不渲染（含系统管理员）。
 *
 * 本测试把这条合同变成可执行的守卫：从 Flyway 迁移里解析每个权限点的真实 `menu_type`/`visible`，
 * 再扫描前端源码里所有 `menu.hasPermission('<perm>')` 字面量，要求每个 perm 都能被证明是菜单可达的
 * （至少存在一条 `MENU` + `visible='Y'` 的行）。这样任何新增的误用都会在 CI 立刻失败，
 * 而不必依赖某个人记得这条规则。
 */

const SRC = resolve(process.cwd(), 'src')
const MIGRATIONS = resolve(process.cwd(), '../backend/src/main/resources/db/migration')

interface PermissionRow {
  readonly type: string
  readonly visible: string
}

/** 从迁移里解析 `perms → (menu_type, visible)`；同一 perm 可能有多行（如 stored-value:me 既有 BUTTON 也有 MENU）。 */
function collectPermissionRows(): Map<string, PermissionRow[]> {
  const files = readdirSync(MIGRATIONS).filter((name) => name.endsWith('.sql')).sort()
  const rows = new Map<string, PermissionRow[]>()
  const add = (perm: string, type: string, visible: string) => {
    const list = rows.get(perm) ?? []
    if (!list.some((row) => row.type === type && row.visible === visible)) list.push({ type, visible })
    rows.set(perm, list)
  }
  for (const name of files) {
    const sql = readFileSync(resolve(MIGRATIONS, name), 'utf8')
    // 形态 A：行内字面量，列序为 (..., perms, menu_type, sort_order, visible, ...)。
    // 覆盖 INSERT ... VALUES 以及 SELECT 里直接写字面量的行。
    for (const match of sql.matchAll(
      /'([a-z][a-z0-9-]*(?::[a-z0-9-]+)+)'\s*,\s*'(MENU|BUTTON)'\s*,\s*[^,]+,\s*'([YN])'/g
    )) {
      add(match[1], match[2], match[3])
    }
    // 形态 B：外层 SELECT 写 `permission_row.perms, 'BUTTON', permission_row.sort_order, 'N'`，
    // 具体权限点写在同一语句的派生表里（`'x:y' AS perms`）。按语句范围配对。
    for (const match of sql.matchAll(
      /permission_row\.`?perms`?\s*,\s*'(MENU|BUTTON)'\s*,\s*permission_row\.`?sort_order`?\s*,\s*'([YN])'/g
    )) {
      const type = match[1]
      const visible = match[2]
      const statement = sql.slice(Math.max(0, match.index - 4000), (match.index ?? 0) + 4000)
      for (const literal of statement.matchAll(/'([a-z][a-z0-9-]*(?::[a-z0-9-]+)+)'\s*AS\s+`?perms`?/g)) {
        add(literal[1], type, visible)
      }
    }
  }
  return rows
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = resolve(dir, entry)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (/\.(vue|ts)$/.test(entry) && !entry.endsWith('.spec.ts')) out.push(full)
  }
  return out
}

/** 源码里所有 `menu.hasPermission('<perm>')` 字面量，附带出现位置便于定位。 */
function collectMenuTreeChecks(): Array<{ perm: string; where: string }> {
  const checks: Array<{ perm: string; where: string }> = []
  for (const file of walk(SRC)) {
    const text = readFileSync(file, 'utf8')
    for (const match of text.matchAll(/menu\.hasPermission\(\s*'([^']+)'\s*\)/g)) {
      checks.push({ perm: match[1], where: file.slice(SRC.length + 1) })
    }
  }
  return checks
}

describe('权限判定通道：菜单树只用于入口，叶子权限点必须走权限快照', () => {
  const rows = collectPermissionRows()
  const menuReachable = new Set(
    [...rows.entries()]
      .filter(([, list]) => list.some((row) => row.type === 'MENU' && row.visible === 'Y'))
      .map(([perm]) => perm)
  )

  it('迁移解析不空转：能同时解析出入口 MENU 与隐藏 BUTTON 叶子', () => {
    // 防止 SQL 形态变化后正则失配，让下面的断言在空集合上「通过」。
    expect(rows.size).toBeGreaterThanOrEqual(40)
    expect(menuReachable.size).toBeGreaterThanOrEqual(25)
    expect(rows.get('promotion:manage')).toEqual([{ type: 'MENU', visible: 'Y' }])
    expect(rows.get('coupon:manage')).toEqual([{ type: 'BUTTON', visible: 'N' }])
    expect(rows.get('pricing:manage')).toEqual([{ type: 'BUTTON', visible: 'N' }])
    expect(rows.get('member-reward:catalog:manage')).toEqual([{ type: 'BUTTON', visible: 'N' }])
    expect(rows.get('community:report:review')).toEqual([{ type: 'BUTTON', visible: 'N' }])
    // stored-value:me 同时存在隐藏 BUTTON 与用户端 MENU，属「任一行为 MENU+Y 即可达」的样例。
    expect(rows.get('stored-value:me')).toEqual(
      expect.arrayContaining([{ type: 'BUTTON', visible: 'N' }, { type: 'MENU', visible: 'Y' }])
    )
  })

  it('扫描不空转：确实存在若干菜单树权限判定', () => {
    expect(collectMenuTreeChecks().length).toBeGreaterThanOrEqual(8)
  })

  it('没有任何叶子权限点被用菜单树判定', () => {
    const offenders = collectMenuTreeChecks()
      .filter((check) => !menuReachable.has(check.perm))
      .map((check) => `${check.where}: menu.hasPermission('${check.perm}')`)
    expect(offenders).toEqual([])
  })

  it('B4-P1 直接回归：促销面的三个权限点各走正确通道', () => {
    // S05 起旧正文 PromotionsView.vue 已由拾光营销面替换，回归目标随之指向新面。
    const view = readFileSync(resolve(SRC, 'views/admin/shiguang/promotion/ShiguangPromotionAdminView.vue'), 'utf8')
    expect(view).toContain("campaigns: 'promotion:manage'")
    expect(view).toContain("coupons: 'coupon:manage'")
    expect(view).toContain("rules: 'pricing:manage'")
    expect(view).toContain('auth.hasPermission(TAB_PERMISSIONS')
    expect(view).not.toContain('menu.hasPermission')
  })

  it('B4-P1 直接回归：会员面的五个页签同样走权限快照', () => {
    const view = readFileSync(resolve(SRC, 'views/admin/shiguang/member/ShiguangMemberAdminView.vue'), 'utf8')
    expect(view).toContain("'member:manage'")
    expect(view).toContain("'member-reward:catalog:manage'")
    expect(view).toContain("'member-reward:exception:manage'")
    expect(view).toContain("'member-reward:audit:view'")
    expect(view).toContain('auth.hasPermission(TAB_PERMISSIONS')
    expect(view).not.toContain('menu.hasPermission')
  })
})
