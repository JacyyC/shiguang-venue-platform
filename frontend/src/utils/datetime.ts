const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

export const parseDate = (value?: string | Date | null) => {
  if (!value) return null
  const date = value instanceof Date ? new Date(value) : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

export const formatDate = (value?: string | Date | null) => {
  const date = parseDate(value)
  if (!date) return value ? String(value).replace('T', ' ').slice(0, 10) : ''
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export const formatShortDate = (value?: string | Date | null) => {
  const date = parseDate(value)
  return date ? `${date.getMonth() + 1}月${date.getDate()}日` : ''
}

export const formatDateTime = (value?: string | Date | null) => {
  const date = parseDate(value)
  if (!date) return value ? String(value).replace('T', ' ').slice(0, 16) : ''
  return `${formatDate(date)} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

export const formatDateTimeZh = (value?: string | Date | null) => {
  const date = parseDate(value)
  if (!date) return value ? String(value).replace('T', ' ') : ''
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

export const formatLongDate = (value?: string | Date | null) => {
  const date = parseDate(value)
  if (!date) return value ? String(value).replace('T', ' ').slice(0, 10) : ''
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${WEEKDAYS[date.getDay()]}`
}

export const formatMonthDayWeek = (value?: string | Date | null) => {
  const date = parseDate(value)
  return date ? `${date.getMonth() + 1}月${date.getDate()}日 ${WEEKDAYS[date.getDay()]}` : ''
}

export const formatMonthDayTime = (value?: string | Date | null) => {
  const date = parseDate(value)
  return date ? `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}` : ''
}

export const formatCompactDateTime = (value?: string | Date | null) => {
  const date = parseDate(value)
  return date ? `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}` : ''
}

export const formatClock = (value?: string | Date | null) => {
  const date = parseDate(value)
  return date ? `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}` : ''
}

/** 营业时间等后端纯时间文本（HH:mm / HH:mm:ss）不走 Date 解析，直接收敛为 HH:mm；无有效时间时回退。 */
export const formatClockText = (value?: string | null, fallback = '') => {
  const matched = value?.match(/(\d{1,2}):(\d{2})/)?.[0]
  return matched ? matched.padStart(5, '0') : fallback
}
