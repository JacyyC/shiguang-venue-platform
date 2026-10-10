import { describe, expect, it } from 'vitest'
import { authorAvatarChar, authorDisplayName, localizeOrderText, maskPhone, maskVenuePhone, orderShortCode } from './entityNames'

describe('orderShortCode 单号短码规则', () => {
  it('标准单号取末 6 位（去前缀与日期段）', () => {
    expect(orderShortCode('CRD20260826165727994898B')).toBe('94898B')
    expect(orderShortCode('ORD2026082623404076691472')).toBe('691472')
  })

  it('空值返回空串', () => {
    expect(orderShortCode(undefined)).toBe('')
    expect(orderShortCode(null)).toBe('')
    expect(orderShortCode('')).toBe('')
  })

  it('异常长度或形状的单号原样展示', () => {
    expect(orderShortCode('CRD20260826ABC')).toBe('CRD20260826ABC')
    expect(orderShortCode('CRD12345')).toBe('CRD12345')
    expect(orderShortCode('XYZ20260826165727994898B')).toBe('XYZ20260826165727994898B')
    expect(orderShortCode('local-order-1')).toBe('local-order-1')
    expect(orderShortCode('PAY202608262340408523758')).toBe('PAY202608262340408523758')
  })
})

describe('localizeOrderText 消息正文单号改写', () => {
  it('正文中的裸单号替换为短码', () => {
    expect(localizeOrderText('订单 CRD20260826165727994898B 已支付成功。')).toBe('订单 94898B 已支付成功。')
    expect(localizeOrderText('订单 ORD2026082623404076691472 已支付成功。')).toBe('订单 691472 已支付成功。')
  })

  it('多个单号全部替换，无单号文本与支付流水号原样保留', () => {
    expect(localizeOrderText('订单 CRD20260826165727994898B 与 ORD20260827093012001122A 均已退款。')).toBe(
      '订单 94898B 与 01122A 均已退款。'
    )
    expect(localizeOrderText('预约已提交，请等待场馆管理员审核。')).toBe('预约已提交，请等待场馆管理员审核。')
    expect(localizeOrderText('支付流水 PAY202608262340408523758 已记录。')).toBe('支付流水 PAY202608262340408523758 已记录。')
  })
})

describe('maskPhone 列表态手机号脱敏', () => {
  it('11 位手机号中间四位打码', () => {
    expect(maskPhone('13812341234')).toBe('138****1234')
  })

  it('非 11 位或空值原样/空串', () => {
    expect(maskPhone('028-85331234')).toBe('028-85331234')
    expect(maskPhone('')).toBe('')
    expect(maskPhone(undefined)).toBe('')
  })
})

describe('authorDisplayName 作者展示名', () => {
  it('正常昵称直接展示', () => {
    expect(authorDisplayName({ id: 2, nickname: '林晓' })).toBe('林晓')
  })

  it('已注销账号（无 id 或空昵称）显示已注销用户', () => {
    expect(authorDisplayName({ id: null, nickname: '社区用户' })).toBe('已注销用户')
    expect(authorDisplayName({ id: 3, nickname: '  ' })).toBe('已注销用户')
    expect(authorDisplayName(undefined)).toBe('已注销用户')
  })

  it('首字头像取昵称首字', () => {
    expect(authorAvatarChar({ id: 2, nickname: '林晓' })).toBe('林')
    expect(authorAvatarChar(undefined)).toBe('社')
  })
})

describe('maskVenuePhone 场馆电话脱敏（R-06.2 分规则）', () => {
  it('座机区号可读：02885338123 → 028-8533****', () => {
    expect(maskVenuePhone('02885338123')).toBe('028-8533****')
    expect(maskVenuePhone('028-8533 8123')).toBe('028-8533****')
  })

  it('手机保持 138****1234', () => {
    expect(maskVenuePhone('13812341234')).toBe('138****1234')
  })

  it('10 位座机（区号+7 位号码）同样区号可读', () => {
    expect(maskVenuePhone('0288533812')).toBe('028-8533****')
  })

  it('无法识别的形状原样返回，空值返回空串', () => {
    expect(maskVenuePhone('12345')).toBe('12345')
    expect(maskVenuePhone('')).toBe('')
    expect(maskVenuePhone(undefined)).toBe('')
  })
})
