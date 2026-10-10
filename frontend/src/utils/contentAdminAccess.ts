export const CONTENT_ADMIN_ENTRY_PERMISSION = 'content-admin:view'

export const canAccessContentAdmin = (permissions: readonly string[]) =>
  permissions.includes(CONTENT_ADMIN_ENTRY_PERMISSION)
