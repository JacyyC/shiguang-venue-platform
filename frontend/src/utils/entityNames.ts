

/** 订单号短码：完整单号去掉三字母前缀（当前系统 ORD，历史样本 CRD）与日期段后取末 6 位
 *  （ORD202608262340407691472 → 91472 / CRD20260826165727994898B → 94898B）。
 *  形状不符（前缀/日期段缺失或剩余长度不足）的异常单号原样返回，不做截断。 */
export const orderShortCode = (orderNo?: string | null): string => {
  if (!orderNo) return ''
  return /^(?:ORD|CRD)\d{8}.{6,}$/.test(orderNo) ? orderNo.slice(-6) : orderNo
}

/** 消息正文等后端拼好的文本里的裸单号统一改写为短码；非单号 token（含支付流水 PAY…）原样保留。 */
export const localizeOrderText = (text?: string | null): string =>
  text ? text.replace(/\b(?:ORD|CRD)\d{8}[0-9A-Za-z]*/g, (token) => orderShortCode(token)) : ''

/** 列表态联系人手机号脱敏：11 位手机号 138****1234；其他形状原样返回（详情/凭证页看全号）。 */
export const maskPhone = (phone?: string | null): string =>
  phone && /^\d{11}$/.test(phone) ? `${phone.slice(0, 3)}****${phone.slice(7)}` : (phone || '')

/** 场馆电话脱敏（R-06.2 分规则）：手机保持 138****1234；座机区号可读——
 *  02885338123 → 028-8533****（前 3 位区号 + - + 号码前 4 位 + ****）。
 *  无法识别的形状原样返回，绝不输出半截误掩。 */
export const maskVenuePhone = (phone?: string | null): string => {
  if (!phone) return ''
  const digits = phone.replace(/\D/g, '')
  if (/^1\d{10}$/.test(digits)) return `${digits.slice(0, 3)}****${digits.slice(7)}`
  if (/^0\d{9,10}$/.test(digits)) return `${digits.slice(0, 3)}-${digits.slice(3, 7)}****`
  return phone
}

export interface AuthorSummaryLike {
  id?: number | null
  nickname?: string | null
}

/** 作者行展示名：后端对已注销账号返回空 id/空昵称，统一显示「已注销用户」；
 *  author 缺失（解析失败）时回落到中性「社区用户」，绝不回退到 ID。 */
export const authorDisplayName = (author?: AuthorSummaryLike | null): string => {
  if (!author || !author.id) return '已注销用户'
  const nickname = author.nickname?.trim()
  return nickname || '已注销用户'
}

/** 首字头像字符：取昵称首字；无昵称可用时用「社」。 */
export const authorAvatarChar = (author?: AuthorSummaryLike | null): string => {
  const nickname = author?.nickname?.trim()
  return nickname ? nickname.charAt(0).toUpperCase() : '社'
}

/** 前端实体名映射：预约/凭证/订单等载荷只带 venueId/spaceId 时，用既有公开接口补名称，不新增后端字段。
 *  模块级缓存去重；venueApi 走函数内动态导入，避免把 http/router 链路拖进纯工具的引用面。
 *  失败返回 null，由调用方回落到无 ID 的中性文案。 */
const venueNameCache = new Map<number, string>()
const spaceNameCache = new Map<string, string>()
const inflight = new Map<string, Promise<unknown>>()

const once = <T>(key: string, loader: () => Promise<T>): Promise<T> => {
  const existing = inflight.get(key)
  if (existing) return existing as Promise<T>
  const promise = loader().finally(() => inflight.delete(key))
  inflight.set(key, promise)
  return promise
}

export const fetchVenueName = async (venueId: number): Promise<string | null> => {
  const cached = venueNameCache.get(venueId)
  if (cached) return cached
  try {
    const { venueApi } = await import('@/api/venue')
    const venue = await once(`venue:${venueId}`, () => venueApi.detail(venueId))
    venueNameCache.set(venueId, venue.name)
    return venue.name
  } catch {
    return null
  }
}

export const fetchSpaceName = async (venueId: number, spaceId: number): Promise<string | null> => {
  const key = `${venueId}:${spaceId}`
  const cached = spaceNameCache.get(key)
  if (cached) return cached
  try {
    const { venueApi } = await import('@/api/venue')
    const spaces = await once(`spaces:${venueId}`, () => venueApi.spaces(venueId))
    const name = spaces.find((space) => space.id === spaceId)?.name ?? null
    if (name) spaceNameCache.set(key, name)
    return name
  } catch {
    return null
  }
}
