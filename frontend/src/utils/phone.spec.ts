import { describe, expect, it } from 'vitest'
import {
  CHINA_MOBILE_PATTERN,
  OPTIONAL_CHINA_MOBILE_MESSAGE,
  isBlankPhone,
  isValidOptionalChinaMobile
} from './phone'

describe('可选大陆手机号口径', () => {
  it('与后端 @OptionalChinaMobile 共享同一正则与文案', () => {
    expect(CHINA_MOBILE_PATTERN.source).toBe('^1[3-9]\\d{9}$')
    expect(OPTIONAL_CHINA_MOBILE_MESSAGE).toBe('请输入 11 位有效手机号')
  })

  it('null、空串与纯空白都表示未填写', () => {
    expect(isBlankPhone(undefined)).toBe(true)
    expect(isBlankPhone(null)).toBe(true)
    expect(isBlankPhone('')).toBe(true)
    expect(isBlankPhone('   ')).toBe(true)
    expect(isValidOptionalChinaMobile(undefined)).toBe(true)
    expect(isValidOptionalChinaMobile('')).toBe(true)
    expect(isValidOptionalChinaMobile('   ')).toBe(true)
  })

  it('11 位大陆手机号通过且允许首尾空白', () => {
    expect(isValidOptionalChinaMobile('13800138000')).toBe(true)
    expect(isValidOptionalChinaMobile('13000000000')).toBe(true)
    expect(isValidOptionalChinaMobile('19999999999')).toBe(true)
    expect(isValidOptionalChinaMobile(' 13800138000 ')).toBe(true)
  })

  it('前缀、长度或字符不合法时拒绝', () => {
    for (const invalid of ['1', '12345', 'abc123', '12800138000', '10800138000', '23800138000',
      '1380013800', '138001380001', '+8613800138000', '138 0013 8000', '1'.repeat(32)]) {
      expect(isValidOptionalChinaMobile(invalid)).toBe(false)
    }
  })
})
