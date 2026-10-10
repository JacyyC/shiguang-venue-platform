const ENVIRONMENT_LABELS: Record<string, string> = {
  DEV: '开发',
  SANDBOX: '沙箱',
  PROD: '生产',
  PRODUCTION: '生产'
}

const CHANNEL_LABELS: Record<string, string> = {
  IN_APP: '站内消息',
  IN_APP_MESSAGE: '站内消息',
  EMAIL: '邮件',
  SMTP_EMAIL: '邮件',
  ALIPAY: '支付宝',
  DEV_PAYMENT: '开发支付',
  DEV_STORED_VALUE: '开发储值',
  ALIPAY_STORED_VALUE: '支付宝储值'
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  ACCOUNT: '储值账户',
  RECHARGE: '充值记录',
  REFUND: '退款记录',
  RECONCILIATION_DIFFERENCE: '对账差异',
  GROUP: '小组',
  POST: '帖子',
  COMMENT: '评论',
  ACTIVITY: '活动',
  COMPANION_REQUEST: '找搭子需求'
}

const BUCKET_LABELS: Record<string, string> = {
  PRINCIPAL_AVAILABLE: '可用本金',
  PRINCIPAL_FROZEN: '冻结本金',
  BONUS_AVAILABLE: '可用赠送金',
  BONUS_FROZEN: '冻结赠送金',
  PRINCIPAL: '本金',
  BONUS: '赠送金'
}

const SCOPE_TYPE_LABELS: Record<string, string> = {
  FULL: '全部账户',
  STORED_VALUE_FULL: '全部储值账户',
  PROVIDER: '指定渠道',
  VENUE: '指定场馆',
  USER: '指定用户'
}

const SOURCE_TYPE_LABELS: Record<string, string> = {
  ADMIN: '管理员操作',
  MANUAL: '人工操作',
  SCHEDULED: '定时任务',
  SYSTEM: '系统触发',
  DEV_SIMULATION: '开发环境模拟',
  PROVIDER_CALLBACK: '渠道回调',
  RECHARGE: '充值',
  PAYMENT: '支付',
  REFUND: '退款',
  ADJUSTMENT: '人工调整'
}

const DIFFERENCE_TYPE_LABELS: Record<string, string> = {
  LOCAL_ONLY: '仅系统有记录',
  PROVIDER_ONLY: '仅渠道有记录',
  AMOUNT_MISMATCH: '金额不一致',
  STATUS_MISMATCH: '状态不一致'
}

const PAYMENT_FAILURE_LABELS: Record<string, string> = {
  SIGNATURE_INVALID: '签名无效',
  ALIPAY_SIGNATURE_INVALID: '支付宝签名无效',
  AMOUNT_MISMATCH: '金额不一致',
  ORDER_STATE_INVALID: '订单状态无效',
  PAYMENT_CREATE_FAILED: '支付创建失败',
  ALIPAY_PAGE_PAY_CREATE_FAILED: '支付宝支付创建失败',
  ALIPAY_TRADE_QUERY_FAILED: '支付宝交易查询失败',
  ALIPAY_BILL_NOT_READY: '支付宝账单尚未就绪',
  ALIPAY_BILL_DOWNLOAD_REJECTED: '支付宝账单下载被拒绝',
  ALIPAY_BILL_SCHEMA_UNSUPPORTED: '支付宝账单格式不受支持',
  ALIPAY_BILL_PARSE_FAILED: '支付宝账单解析失败'
}

const BUSINESS_TYPE_LABELS: Record<string, string> = {
  ORDER_PAYMENT: '订单支付',
  PAYMENT: '支付',
  REFUND: '退款',
  STORED_VALUE_RECHARGE: '储值充值'
}

const TRANSACTION_TYPE_LABELS: Record<string, string> = {
  RECHARGE: '充值入账',
  RECHARGE_LIMIT_RELEASE: '充值限额释放',
  PAYMENT: '消费支付',
  REFUND: '退款',
  ACCOUNT_FREEZE: '账户冻结',
  ACCOUNT_UNFREEZE: '账户解冻',
  ACCOUNT_CLOSE: '账户注销',
  ADJUSTMENT: '人工调整'
}

const readable = (value: string | null | undefined, labels: Record<string, string>, fallback: string) => {
  if (!value) return '-'
  return labels[value] || fallback
}

export const environmentLabel = (value?: string | null) => readable(value, ENVIRONMENT_LABELS, '未知环境')
export const localizeEnvironmentText = (value?: string | null) => value
  ? value.replace(/\b(?:DEV|SANDBOX|PROD|PRODUCTION)\b/g, (environment) => environmentLabel(environment))
  : '-'
export const channelLabel = (value?: string | null) => readable(value, CHANNEL_LABELS, '其他渠道')
export const targetTypeLabel = (value?: string | null) => readable(value, TARGET_TYPE_LABELS, '其他业务对象')
export const bucketLabel = (value?: string | null) => readable(value, BUCKET_LABELS, '其他资金分类')
export const scopeTypeLabel = (value?: string | null) => readable(value, SCOPE_TYPE_LABELS, '其他范围')
export const sourceTypeLabel = (value?: string | null) => readable(value, SOURCE_TYPE_LABELS, '其他来源')
export const differenceTypeLabel = (value?: string | null) => readable(value, DIFFERENCE_TYPE_LABELS, '其他差异')
export const paymentFailureLabel = (value?: string | null) => readable(value, PAYMENT_FAILURE_LABELS, '其他失败原因')
export const businessTypeLabel = (value?: string | null) => readable(value, BUSINESS_TYPE_LABELS, '其他业务')
export const transactionTypeLabel = (value?: string | null) => readable(value, TRANSACTION_TYPE_LABELS, '其他交易')

/**
 * EP 彩色 tag 清零（V3-UI-UX-SHIGUANG AC-05「el-tag→.sg-tag 章」）：
 * 把 Element Plus 的 `type` 语义翻译为 .sg-tag 语义章 tone 类，`info`/未知归 neutral。
 * 只返回 tone 后缀，调用方以 `class="sg-tag" :class="sgTagTone(x)"` 拼装，
 * 与 SiteOps/Logs/RewardCatalog 既有本地 tone 助手同口径（不改动任何状态语义）。
 */
const SG_TAG_TONES: Record<string, string> = {
  success: 'success',
  warning: 'warning',
  danger: 'danger',
  primary: 'primary'
}

export const sgTagTone = (type?: string | null) => `sg-tag--${SG_TAG_TONES[type || ''] || 'neutral'}`
