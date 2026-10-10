/**
 * 金额展示工具（§06 红线「重复实现」：formatMoney 在 12 个视图里各自实现、逐份漂移，统一上提此处）。
 *
 * 三档既有输出形态原样保留——金额显示逻辑零改动，只收敛实现来源：
 * - `formatMoney`：全角 ￥ + 两位小数，无千分位（用户端票据与金额的主流形态）
 * - `formatMoneyGrouped`：全角 ￥ + 千分位 + 两位小数（管理端财务/对账/会员台账）
 * - `formatCurrencyCNY`：`Intl` 的 CNY 货币形态（场馆运营订单，半角 ¥ + 千分位）
 *
 * 三者不可互换：换档会改变页面上实际显示的金额字符串。
 */

export const formatMoney = (value?: number): string => `￥${Number(value || 0).toFixed(2)}`

export const formatMoneyGrouped = (value?: number): string =>
  `￥${Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`

const CNY_FORMATTER = new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  minimumFractionDigits: 2
})

export const formatCurrencyCNY = (value?: number): string => CNY_FORMATTER.format(Number(value || 0))

/**
 * 拾光预约链路的数字金额形态（SHIGUANG-BOOKING-REFACTOR-20260910）。
 *
 * 取自冻结参考的 `money()`：整数不补小数位、非整数保留两位，不带货币符号
 * （`¥` 由版式在模板里给出，与 `--mono` 数字同处一行）。仅服务 `.sgv-booking`
 * 作用域内的价格排版，不替换上面三档既有形态。
 */
export const formatShiguangAmount = (value?: number | string | null): string => {
  const amount = Number(value || 0)
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2)
}

/**
 * 拾光财务面的金额形态（SHIGUANG-BATCH-05-REFACTOR-20260915 S04）。
 *
 * 取自冻结参考 `ref/h/finance.js:4` 的 `money()`：半角 `¥` + 空格 + 固定两位小数，
 * 无千分位。仅服务 `.sga-fin` 作用域（支付与储值财务），不替换上面三档既有形态：
 * 财务台账负数差额要原样呈现为「¥ -2.00」，换成 `formatMoneyGrouped` 会改变字符串。
 */
export const formatShiguangFinanceMoney = (value?: number | string | null): string =>
  `¥ ${Number(value || 0).toFixed(2)}`
