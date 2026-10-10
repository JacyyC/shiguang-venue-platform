import { describe, expect, it } from 'vitest'
import { formatClockText } from './datetime'

describe('formatClockText 全局 HH:mm 收敛', () => {
  it('秒级营业时间去掉秒位', () => {
    expect(formatClockText('08:00:00')).toBe('08:00')
    expect(formatClockText('22:30:00')).toBe('22:30')
  })

  it('已是 HH:mm 的值原样保留', () => {
    expect(formatClockText('09:05')).toBe('09:05')
  })

  it('个位小时补零', () => {
    expect(formatClockText('8:30')).toBe('08:30')
  })

  it('空值与非时间文本回退到 fallback', () => {
    expect(formatClockText(undefined, '08:00')).toBe('08:00')
    expect(formatClockText('', '--:--')).toBe('--:--')
    expect(formatClockText('全天', '08:00')).toBe('08:00')
  })
})
