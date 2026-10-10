import { describe, expect, it } from 'vitest'
import {
  businessTypeLabel,
  bucketLabel,
  channelLabel,
  differenceTypeLabel,
  environmentLabel,
  localizeEnvironmentText,
  paymentFailureLabel,
  scopeTypeLabel,
  sgTagTone,
  sourceTypeLabel,
  targetTypeLabel,
  transactionTypeLabel
} from './presentation'

describe('presentation labels', () => {
  it('maps environments and channels to operator-facing Chinese', () => {
    expect(environmentLabel('DEV')).toBe('开发')
    expect(environmentLabel('SANDBOX')).toBe('沙箱')
    expect(environmentLabel('PROD')).toBe('生产')
    expect(localizeEnvironmentText('当前没有启用的 DEV/SANDBOX 储值产品')).toBe('当前没有启用的 开发/沙箱 储值产品')
    expect(channelLabel('IN_APP_MESSAGE')).toBe('站内消息')
    expect(channelLabel('SMTP_EMAIL')).toBe('邮件')
  })

  it('maps technical business enums without echoing unknown values', () => {
    expect(targetTypeLabel('RECHARGE')).toBe('充值记录')
    expect(bucketLabel('PRINCIPAL_AVAILABLE')).toBe('可用本金')
    expect(scopeTypeLabel('STORED_VALUE_FULL')).toBe('全部储值账户')
    expect(sourceTypeLabel('SCHEDULED')).toBe('定时任务')
    expect(differenceTypeLabel('AMOUNT_MISMATCH')).toBe('金额不一致')
    expect(paymentFailureLabel('SIGNATURE_INVALID')).toBe('签名无效')
    expect(businessTypeLabel('ORDER_PAYMENT')).toBe('订单支付')
    expect(transactionTypeLabel('ACCOUNT_FREEZE')).toBe('账户冻结')
    expect(targetTypeLabel('FUTURE_INTERNAL_ENUM')).toBe('其他业务对象')
  })

  it('translates Element Plus tag types into the semantic stamp family (AC-05)', () => {
    expect(sgTagTone('success')).toBe('sg-tag--success')
    expect(sgTagTone('warning')).toBe('sg-tag--warning')
    expect(sgTagTone('danger')).toBe('sg-tag--danger')
    expect(sgTagTone('primary')).toBe('sg-tag--primary')
    // info 与未知/空值一律归 neutral，避免全局语义章之外残留彩色 tag
    expect(sgTagTone('info')).toBe('sg-tag--neutral')
    expect(sgTagTone(undefined)).toBe('sg-tag--neutral')
    expect(sgTagTone(null)).toBe('sg-tag--neutral')
    expect(sgTagTone('FUTURE_EP_TYPE')).toBe('sg-tag--neutral')
  })
})
