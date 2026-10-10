/**
 * 可选大陆手机号口径（与后端 `@OptionalChinaMobile` / `^1[3-9]\d{9}$` 一致）。
 *
 * 这里只做「提交前提示」，服务端约束才是最终合同：未填写（null/空串/纯空白）表示不填写，
 * 非空值必须是 11 位大陆手机号。前端不引入第二套规则，也不替代服务端校验。
 */
export const CHINA_MOBILE_PATTERN = /^1[3-9]\d{9}$/

export const OPTIONAL_CHINA_MOBILE_MESSAGE = '请输入 11 位有效手机号'

export function isBlankPhone(value?: string | null): boolean {
  return !value || !value.trim().length
}

export function isValidOptionalChinaMobile(value?: string | null): boolean {
  if (isBlankPhone(value)) return true
  return CHINA_MOBILE_PATTERN.test(String(value).trim())
}
