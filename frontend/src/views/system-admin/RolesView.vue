<template>
  <section class="admin-page roles-page">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">平台治理 · 权限模型</p>
        <h1 class="sg-display-l page-title">角色权限 · 授权工作台</h1>
        <p class="section-desc">覆盖全部身份：内置角色可查、自建岗位可查可授；默认种子不动，授权动作经界面留痕。</p>
      </div>
      <div class="toolbar-actions">
        <el-button v-if="canManageRoles" type="primary" size="small" @click="openCreateRole">
          <el-icon><Plus /></el-icon>
          新建角色
        </el-button>
      </div>
    </div>

    <p v-if="pageError" class="inline-feedback is-error" role="alert">{{ pageError }}</p>

    <div class="data-panel perm-panel">
      <el-skeleton v-if="loading && !roles.length" :rows="6" animated />
      <template v-else>
        <div class="perm-grid">
          <div class="role-list" role="listbox" aria-label="角色列表">
            <button
              v-for="role in roles"
              :key="role.id"
              type="button"
              class="role-item"
              :class="{ 'is-on': role.id === selectedRoleId }"
              :aria-selected="role.id === selectedRoleId"
              @click="selectRole(role.id)"
            >
              {{ role.name }}
              <small>{{ roleCaption(role) }}</small>
            </button>
            <button
              v-if="canManageRoles"
              type="button"
              class="role-item create"
              @click="openCreateRole"
            >＋ 新建角色</button>
          </div>

          <div class="perm-body">
            <p v-if="!selectedRole" class="muted-text">请选择左侧角色查看其权限。</p>
            <template v-else>
              <div class="perm-meta">
                <span class="perm-meta-title">{{ selectedRole.name }}</span>
                <StatusTag :value="selectedRole.status === 'ACTIVE' ? 'ENABLED' : 'DISABLED'" kind="provider" />
                <span v-if="selectedRole.builtIn" class="perm-readonly-hint">内置角色不可修改，此处仅作查看</span>
              </div>
              <p v-if="roleError" class="inline-feedback is-error" role="alert">{{ roleError }}</p>
              <el-skeleton v-if="permissionsLoading" :rows="5" animated />
              <template v-else>
                <div class="perm-toolbar">
                  <input
                    v-model="permissionQuery"
                    class="perm-search"
                    type="search"
                    placeholder="搜索权限名称或标识（如 placement）"
                    aria-label="搜索权限名称或标识"
                  />
                  <span class="perm-total-chip">已选 {{ checkedIds.length }} / {{ menus.length }}</span>
                </div>

                <section
                  v-for="group in filteredPermissionGroups"
                  :key="group.label"
                  class="perm-group"
                  :class="{ 'is-open': isGroupOpen(group.label) }"
                  :aria-label="`${group.label}权限`"
                >
                  <div class="perm-group-head">
                    <button
                      type="button"
                      class="perm-group-toggle"
                      :aria-expanded="isGroupOpen(group.label)"
                      @click="toggleGroupOpen(group.label)"
                    >
                      <span class="perm-caret" aria-hidden="true">▶</span>
                      <span class="perm-group-name">{{ group.label }}</span>
                      <small v-if="groupPrefixHint(group)" class="perm-group-keys">{{ groupPrefixHint(group) }}</small>
                    </button>
                    <div class="perm-group-meta">
                      <span class="perm-group-count">
                        已选 <b :class="{ 'is-hot': selectedCountOf(group) > 0 }">{{ selectedCountOf(group) }}</b> / 共 {{ group.menus.length }}
                      </span>
                      <button
                        v-if="canManageRoles"
                        type="button"
                        class="perm-select-all"
                        :disabled="selectedRole.builtIn || !group.menus.length"
                        @click="toggleGroupAll(group)"
                      >{{ isGroupAllSelected(group) ? '全不选' : '全选' }}</button>
                    </div>
                  </div>
                  <div v-if="isGroupOpen(group.label)" class="perm-group-body">
                    <label
                      v-for="menu in group.menus"
                      :key="menu.id"
                      class="perm-row"
                      :class="{ 'is-hit': isRowHit(menu) }"
                    >
                      <input
                        v-model="checkedIds"
                        type="checkbox"
                        :value="menu.id"
                        :disabled="selectedRole?.builtIn"
                      />
                      <span class="perm-name">{{ permissionLabel(menu) }}</span>
                      <span class="pk">{{ menu.permission }}</span>
                    </label>
                  </div>
                </section>
                <p v-if="searchActive && !filteredPermissionGroups.length" class="muted-text">
                  没有匹配「{{ permissionQuery }}」的权限，请换一个关键词。
                </p>
                <p class="perm-footnote">成员账号管理与角色授权调整两项系统权限不参与下放，由后端保留。</p>
                <div v-if="canManageRoles" class="perm-savebar" data-test="perm-savebar">
                  <span class="perm-savebar-hint">保存自动补齐可见子菜单的父级链 · 内置角色只读</span>
                  <el-button
                    type="primary"
                    :disabled="selectedRole.builtIn || !dirty"
                    :loading="saving"
                    @click="savePermissions"
                  >{{ saveButtonLabel }}</el-button>
                </div>
              </template>
            </template>
          </div>
        </div>
      </template>
    </div>

    <el-dialog v-model="createOpen" title="新建角色" width="min(480px, calc(100vw - 24px))">
      <div class="role-form">
        <p class="section-desc">创建后即可为该岗位勾选并保存叶子权限；角色标识创建后不可更改。</p>
        <label>
          <span class="field-label">角色名称</span>
          <input v-model="createForm.roleName" type="text" maxlength="64" placeholder="例如：客服专员" />
        </label>
        <label>
          <span class="field-label">角色标识</span>
          <input
            v-model="createForm.roleKey"
            type="text"
            maxlength="64"
            placeholder="例如 CUSTOM_SERVICE（字母开头，可含数字与下划线）"
          />
        </label>
        <p v-if="formError" class="inline-feedback is-error" role="alert">{{ formError }}</p>
      </div>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createRole">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/StatusTag.vue'
import { systemApi } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import type { ManagedRole, PermissionMenu } from '@/api/system-types'

interface PermissionGroup {
  prefix: string
  label: string
  menus: PermissionMenu[]
}

// 分组词表沿用现网工作区命名；预览点名的三组排前，其余域按既有后台分组语言延伸。
const GROUP_RULES: Array<{ prefix: string; label: string }> = [
  { prefix: 'content-admin', label: '社 区 治 理' },
  { prefix: 'community', label: '社 区 治 理' },
  { prefix: 'placement', label: '宣 传 与 广 告' },
  { prefix: 'advertising', label: '宣 传 与 广 告' },
  { prefix: 'system', label: '平 台 治 理' },
  { prefix: 'venue', label: '空 间 运 营' },
  { prefix: 'reservation', label: '空 间 运 营' },
  { prefix: 'site:', label: '空 间 运 营' },
  { prefix: 'asset:', label: '空 间 运 营' },
  { prefix: 'customer-service', label: '空 间 运 营' },
  { prefix: 'funnel', label: '空 间 运 营' },
  { prefix: 'order', label: '订 单 与 资 金' },
  { prefix: 'payment', label: '订 单 与 资 金' },
  { prefix: 'stored-value', label: '订 单 与 资 金' },
  { prefix: 'member', label: '会 员 营 销' },
  { prefix: 'promotion', label: '会 员 营 销' },
  { prefix: 'coupon', label: '会 员 营 销' },
  { prefix: 'notice', label: '消 息 触 达' },
  { prefix: 'provider', label: '渠 道 观 测' }
]

// 展示名只服务界面阅读；技术串原样保留在行尾（决策 14 边界）。
const PERMISSION_LABELS: Record<string, string> = {
  'community:access': '社区入口',
  'community:report:review': '接单审核举报',
  'community:content:moderate': '内容处置（隐藏 / 恢复）',
  'community:content:restore': '内容恢复',
  'community:audit:view': '治理审计查看',
  'placement:manage': '宣传位候选管理',
  'placement:publish': '宣传位发布控制',
  'placement:audit:view': '宣传位观测查看',
  'advertising:manage': '广告资料管理',
  'advertising:review': '广告内容审核',
  'advertising:publish': '广告发布控制',
  'advertising:analysis:view': '广告效果分析',
  'advertising:audit:view': '广告审计查看',
  'system:view': '平台后台入口',
  'system:user:manage': '成员账号管理',
  'system:role:manage': '角色授权调整',
  'system:config:manage': '运行配置维护',
  'system:log:view': '安全审计日志',
  'stored-value:account:close': '储值账户注销'
}

const BUILT_IN_CAPTIONS: Record<string, string> = {
  USER: '本人数据',
  VENUE_ADMIN: '授权场馆',
  SYSTEM_ADMIN: '全量权限（V35 起）'
}

const auth = useAuthStore()
const canManageRoles = computed(() => auth.hasPermission('system:role:manage'))

const loading = ref(false)
const saving = ref(false)
const creating = ref(false)
const permissionsLoading = ref(false)
const pageError = ref('')
const roleError = ref('')
const formError = ref('')

const roles = ref<ManagedRole[]>([])
const menus = ref<PermissionMenu[]>([])
const selectedRoleId = ref<number>()
const checkedIds = ref<number[]>([])
const savedIds = ref<number[]>([])
const createOpen = ref(false)
const createForm = reactive({ roleName: '', roleKey: '' })
const permissionQuery = ref('')
const expandedGroups = ref<string[]>([])

const selectedRole = computed(() => roles.value.find((role) => role.id === selectedRoleId.value))

const dirty = computed(() => {
  const saved = new Set(savedIds.value)
  const checked = new Set(checkedIds.value)
  if (saved.size !== checked.size) return true
  for (const id of checked) {
    if (!saved.has(id)) return true
  }
  return false
})

const parentById = computed(() => new Map(menus.value.map((menu) => [menu.id, menu])))

const groupOf = (menu: PermissionMenu): { prefix: string; label: string } => {
  const matched = GROUP_RULES.find((rule) => menu.permission.startsWith(rule.prefix))
  return matched ?? { prefix: 'other', label: '其 他' }
}

const GROUP_ORDER = ['社 区 治 理', '宣 传 与 广 告', '平 台 治 理', '空 间 运 营', '订 单 与 资 金', '会 员 营 销', '消 息 触 达', '渠 道 观 测', '其 他']

const permissionGroups = computed<PermissionGroup[]>(() => {
  const grouped = new Map<string, PermissionGroup>()
  const orderedRules = [...GROUP_RULES].sort(
    (left, right) => GROUP_ORDER.indexOf(left.label) - GROUP_ORDER.indexOf(right.label)
  )
  for (const rule of orderedRules) {
    if (!grouped.has(rule.label)) grouped.set(rule.label, { prefix: rule.prefix, label: rule.label, menus: [] })
  }
  grouped.set('其 他', { prefix: 'other', label: '其 他', menus: [] })
  for (const menu of menus.value) {
    const group = groupOf(menu)
    const bucket = grouped.get(group.label)
    if (bucket) bucket.menus.push(menu)
  }
  return [...grouped.values()].filter((group) => group.menus.length)
})

const searchActive = computed(() => permissionQuery.value.trim().length > 0)

const matchesQuery = (menu: PermissionMenu, query: string) =>
  [permissionLabel(menu), menu.name ?? '', menu.permission].some((text) => text.toLowerCase().includes(query))

// 搜索态只保留命中的权限行（供行级高亮）；清空查询后回到完整分组折叠视图。
const filteredPermissionGroups = computed<PermissionGroup[]>(() => {
  const query = permissionQuery.value.trim().toLowerCase()
  if (!query) return permissionGroups.value
  return permissionGroups.value
    .map((group) => ({ ...group, menus: group.menus.filter((menu) => matchesQuery(menu, query)) }))
    .filter((group) => group.menus.length)
})

watch(permissionQuery, (value) => {
  // 清空搜索即恢复默认全部折叠态（Spec C-03.2）。
  if (!value.trim()) expandedGroups.value = []
})

const isGroupOpen = (label: string) => searchActive.value || expandedGroups.value.includes(label)

const toggleGroupOpen = (label: string) => {
  if (searchActive.value) return
  expandedGroups.value = expandedGroups.value.includes(label)
    ? expandedGroups.value.filter((open) => open !== label)
    : [...expandedGroups.value, label]
}

const isRowHit = (menu: PermissionMenu) => {
  const query = permissionQuery.value.trim().toLowerCase()
  return query.length > 0 && matchesQuery(menu, query)
}

const groupPrefixHint = (group: PermissionGroup) => {
  const seen = new Set<string>()
  for (const menu of group.menus) {
    seen.add(groupOf(menu).prefix.trim())
  }
  return [...seen].slice(0, 3).join(' · ')
}

const selectedCountOf = (group: PermissionGroup) =>
  group.menus.reduce((count, menu) => count + (checkedIds.value.includes(menu.id) ? 1 : 0), 0)

const isGroupAllSelected = (group: PermissionGroup) => group.menus.every((menu) => checkedIds.value.includes(menu.id))

const toggleGroupAll = (group: PermissionGroup) => {
  if (selectedRole.value?.builtIn || !group.menus.length) return
  const next = new Set(checkedIds.value)
  if (isGroupAllSelected(group)) {
    group.menus.forEach((menu) => next.delete(menu.id))
  } else {
    group.menus.forEach((menu) => next.add(menu.id))
  }
  checkedIds.value = [...next]
}

const permissionLabel = (menu: PermissionMenu) => PERMISSION_LABELS[menu.permission] || menu.name || menu.permission

const roleCaption = (role: ManagedRole) => role.builtIn
  ? BUILT_IN_CAPTIONS[role.key] || '内置角色'
  : (role.status === 'ACTIVE' ? '自建岗位 · 启用中' : '自建岗位 · 已停用')

const saveButtonLabel = computed(() => (dirty.value ? `保存授权（${checkedIds.value.length} 项）` : '保存授权'))

const loadRolesAndMenus = async () => {
  loading.value = true
  pageError.value = ''
  try {
    const [nextRoles, nextMenus] = await Promise.all([
      systemApi.managedRoles(),
      systemApi.permissionMenus()
    ])
    roles.value = nextRoles
    menus.value = nextMenus
    if (!selectedRoleId.value && nextRoles.length) {
      const preferred = nextRoles.find((role) => !role.builtIn) ?? nextRoles[0]
      selectedRoleId.value = preferred.id
    }
  } catch (caught) {
    pageError.value = extractError(caught, '角色或权限清单加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

const loadRolePermissions = async (roleId: number) => {
  permissionsLoading.value = true
  roleError.value = ''
  try {
    savedIds.value = await systemApi.rolePermissions(roleId)
    checkedIds.value = [...savedIds.value]
  } catch (caught) {
    roleError.value = extractError(caught, '角色权限读取失败，请重试。')
  } finally {
    permissionsLoading.value = false
  }
}

const selectRole = (roleId: number) => {
  if (selectedRoleId.value === roleId) return
  selectedRoleId.value = roleId
}

watch(selectedRoleId, (roleId) => {
  if (roleId) void loadRolePermissions(roleId)
})

// 可见子菜单必须同时分配父级权限（后端校验），保存时自动补齐祖先链。
const withAncestors = (ids: number[]) => {
  const result = new Set(ids)
  for (const id of ids) {
    let current = parentById.value.get(id)
    while (current) {
      result.add(current.id)
      const parentId = current.parentId
      current = parentId ? parentById.value.get(parentId) : undefined
    }
  }
  return [...result]
}

const savePermissions = async () => {
  const role = selectedRole.value
  if (!role || role.builtIn || saving.value) return
  saving.value = true
  roleError.value = ''
  try {
    await systemApi.replaceRolePermissions(role.id, withAncestors(checkedIds.value))
    ElMessage.success(`已保存「${role.name}」的权限`)
    await loadRolePermissions(role.id)
  } catch (caught) {
    roleError.value = extractError(caught, '权限保存失败，请重试。')
  } finally {
    saving.value = false
  }
}

const openCreateRole = () => {
  createForm.roleName = ''
  createForm.roleKey = ''
  formError.value = ''
  createOpen.value = true
}

const createRole = async () => {
  const roleName = createForm.roleName.trim()
  const roleKey = createForm.roleKey.trim()
  if (!roleName || !roleKey) {
    formError.value = '请填写角色名称与角色标识。'
    return
  }
  creating.value = true
  formError.value = ''
  try {
    const created = await systemApi.createManagedRole({ roleName, roleKey })
    createOpen.value = false
    ElMessage.success(`角色「${created.name}」已创建`)
    await loadRolesAndMenus()
    selectedRoleId.value = created.id
  } catch (caught) {
    formError.value = extractError(caught, '角色创建失败，请检查名称与标识。')
  } finally {
    creating.value = false
  }
}

const extractError = (caught: unknown, fallback: string) => {
  const error = caught as { response?: { status?: number }; message?: string }
  if (error.response?.status === 403) return '当前账号缺少 system:role:manage 权限。'
  return error.message && !error.message.includes('Request failed') ? error.message : fallback
}

onMounted(() => {
  void loadRolesAndMenus()
})
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.perm-panel {
  padding: 0;
}

.perm-grid {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  min-height: 320px;
}

.role-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px;
  border-right: 1px solid var(--sg-border-soft);
  border-radius: var(--sg-radius-card) 0 0 var(--sg-radius-card);
  background: var(--sg-surface-subtle);
}

.role-item {
  display: block;
  width: 100%;
  padding: 9px 11px;
  border: 0;
  border-radius: var(--sg-radius-control);
  background: none;
  color: var(--sg-ink-muted);
  font: inherit;
  font-size: var(--sg-fz-sm);
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--sg-motion-fast) ease-out, color var(--sg-motion-fast) ease-out;
}

.role-item:hover {
  background: var(--sg-surface);
}

.role-item.is-on {
  background: var(--sg-primary-soft);
  color: var(--sg-primary);
  font-weight: 600;
}

.role-item:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

.role-item small {
  display: block;
  margin-top: 2px;
  color: var(--sg-ink-subtle);
  font-size: 10.5px;
  font-weight: 400;
}

.role-item.create {
  margin-top: 8px;
  border: 0;
  border-radius: var(--sg-radius-control);
  background: var(--sg-ink);
  color: var(--sg-canvas);
  text-align: center;
}

.perm-body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 320px;
  box-sizing: border-box;
  padding: 16px 20px;
}

.perm-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.perm-meta-title {
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-lg);
  font-weight: 600;
}

.perm-readonly-hint {
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
}

.perm-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.perm-search {
  flex: 1;
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-control);
  background: var(--sg-surface);
  color: var(--sg-ink);
  font: inherit;
  font-size: var(--sg-fz-sm);
}

.perm-search::placeholder {
  color: var(--sg-ink-subtle);
}

.perm-search:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 1px;
}

.perm-total-chip {
  flex: none;
  padding: 5px 12px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-stamp);
  background: var(--sg-surface-subtle);
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  font-weight: 600;
  white-space: nowrap;
}

.perm-group {
  border: 0;
  border-radius: 0;
  background: transparent;
  margin-bottom: 0;
  box-shadow: 0 -1px 0 var(--sg-border-soft);
}

@media (prefers-reduced-motion: no-preference) {
  .perm-group:hover {
    background: color-mix(in srgb, var(--sg-primary) 7%, transparent);
  }
}

.perm-group {
  transition: background-color var(--sg-motion-fast) var(--sg-ease-standard);
}

.perm-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 8px 4px 4px;
}

.perm-group-toggle {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 40px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--sg-radius-control);
  background: none;
  color: var(--sg-ink);
  font: inherit;
  font-size: var(--sg-fz-sm);
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.perm-group-toggle:hover {
  background: var(--sg-surface-subtle);
}

.perm-group-toggle:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 1px;
}

.perm-caret {
  flex: none;
  color: var(--sg-ink-subtle);
  font-size: 10px;
  transition: transform var(--sg-motion-fast) ease-out;
}

.perm-group.is-open .perm-caret {
  transform: rotate(90deg);
}

.perm-group-name {
  white-space: nowrap;
}

.perm-group-keys {
  overflow: hidden;
  max-width: 320px;
  color: var(--sg-ink-subtle);
  font-family: var(--sg-font-mono);
  font-size: 10.5px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.perm-group-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.perm-group-count {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
  white-space: nowrap;
}

.perm-group-count b.is-hot {
  color: var(--sg-primary);
}

.perm-select-all {
  min-height: 28px;
  padding: 4px 12px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-stamp);
  background: var(--sg-surface);
  color: var(--sg-ink-muted);
  font: inherit;
  font-size: var(--sg-fz-xs);
  cursor: pointer;
}

.perm-select-all:hover:not(:disabled) {
  border-color: var(--sg-border-strong);
  color: var(--sg-ink);
}

.perm-select-all:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.perm-select-all:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 1px;
}

.perm-group-body {
  padding: 2px 14px 10px;
}

.perm-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 5px 8px;
  font-size: var(--sg-fz-sm);
  border-radius: var(--sg-radius-control);
  cursor: pointer;
}

.perm-row.is-hit {
  background: var(--sg-primary-soft);
}

.perm-row input[type='checkbox'] {
  width: 15px;
  height: 15px;
  flex: none;
  accent-color: var(--sg-pine);
  cursor: pointer;
}

.perm-row input[type='checkbox']:disabled {
  cursor: not-allowed;
}

.perm-row input[type='checkbox']:focus-visible {
  outline: 2px solid var(--sg-focus);
  outline-offset: 2px;
}

.perm-name {
  min-width: 0;
}

.pk {
  margin-left: auto;
  color: var(--sg-ink-subtle);
  font-family: var(--sg-font-mono);
  font-size: 10.5px;
  overflow-wrap: anywhere;
}

.perm-footnote {
  margin-top: 10px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
}

.perm-savebar {
  position: sticky;
  bottom: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: auto;
  margin-inline: -20px;
  margin-bottom: -16px;
  padding: 12px 20px calc(12px + env(safe-area-inset-bottom));
  background: var(--sg-glass);
  border-top: 1px solid var(--sg-border-soft);
  border-radius: 0 0 var(--sg-radius-card) var(--sg-radius-card);
  -webkit-backdrop-filter: blur(var(--sg-glass-blur)) saturate(1.25);
  backdrop-filter: blur(var(--sg-glass-blur)) saturate(1.25);
  box-shadow: inset 0 1px 0 var(--sg-sheen);
}

.perm-savebar-hint {
  margin-right: auto;
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
}

.role-form {
  display: grid;
  gap: 14px;
}

.role-form label {
  display: grid;
  gap: 6px;
}

.role-form input {
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-control);
  background: var(--sg-surface);
  color: var(--sg-ink);
  font: inherit;
}

.role-form input:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--sg-focus) 30%, transparent);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .perm-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .role-list {
    border-right: 0;
    border-bottom: 1px solid var(--sg-border-soft);
    border-radius: var(--sg-radius-card) var(--sg-radius-card) 0 0;
  }

  .perm-toolbar {
    flex-wrap: wrap;
  }

  .perm-group-keys {
    display: none;
  }
}
</style>
