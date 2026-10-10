import { describe, expect, it } from 'vitest'
import {
  formatCurrencyCNY,
  formatMoney,
  formatMoneyGrouped,
  formatShiguangFinanceMoney
} from './money'

/**
 * 三档金额形态的回归锁：上提 utils 时逐档固化原视图输出，
 * 任何一档的字符串变化都会在这里失败（金额显示逻辑零改动的守门）。
 */
describe('money', () => {
  it('formatMoney keeps the plain two-decimal yuan form without grouping', () => {
    expect(formatMoney(0)).toBe('￥0.00')
    expect(formatMoney(12.5)).toBe('￥12.50')
    expect(formatMoney(1234.5)).toBe('￥1234.50')
    expect(formatMoney(1234567.891)).toBe('￥1234567.89')
    expect(formatMoney()).toBe('￥0.00')
    expect(formatMoney(undefined)).toBe('￥0.00')
  })

  it('formatMoneyGrouped keeps the grouped ledger form with a fullwidth yuan sign', () => {
    expect(formatMoneyGrouped(0)).toBe('￥0.00')
    expect(formatMoneyGrouped(12.5)).toBe('￥12.50')
    expect(formatMoneyGrouped(1234.5)).toBe('￥1,234.50')
    expect(formatMoneyGrouped(1234567.891)).toBe('￥1,234,567.89')
    expect(formatMoneyGrouped()).toBe('￥0.00')
  })

  it('formatCurrencyCNY keeps the Intl CNY form with a halfwidth yuan sign', () => {
    expect(formatCurrencyCNY(0)).toBe('¥0.00')
    expect(formatCurrencyCNY(12.5)).toBe('¥12.50')
    expect(formatCurrencyCNY(1234.5)).toBe('¥1,234.50')
    expect(formatCurrencyCNY(1234567.891)).toBe('¥1,234,567.89')
    expect(formatCurrencyCNY()).toBe('¥0.00')
  })

  // SHIGUANG-BATCH-05-REFACTOR-20260915 S04：财务面沿用 ref/h/finance.js:4 的 money()
  it('formatShiguangFinanceMoney keeps the halfwidth yuan, space and fixed two decimals form', () => {
    expect(formatShiguangFinanceMoney(0)).toBe('¥ 0.00')
    expect(formatShiguangFinanceMoney(80)).toBe('¥ 80.00')
    expect(formatShiguangFinanceMoney(-2)).toBe('¥ -2.00')
    expect(formatShiguangFinanceMoney(1234.5)).toBe('¥ 1234.50')
    expect(formatShiguangFinanceMoney()).toBe('¥ 0.00')
  })
})
