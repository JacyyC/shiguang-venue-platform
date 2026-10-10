/** 两个壳共用工作身份文案与目的地；各壳继续使用既有入口权限判断。 */
export const WORKSPACE_DEFS = [
  { key: 'venue', label: '空间运营', path: '/venue-admin', description: '场馆与用户服务', icon: 'pin' },
  { key: 'platform', label: '平台治理', path: '/system-admin', description: '账号、规则与运行', icon: 'shield' },
  { key: 'content', label: '内容治理', path: '/content-admin', description: '平台内容与宣传', icon: 'bell' }
] as const
