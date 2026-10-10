<template>
  <section class="admin-page">
    <div class="page-head">
      <div>
        <p class="sg-kicker">平台治理 · 账号治理</p>
        <h1 class="sg-display-l page-title">用户管理</h1>
        <p class="section-desc">检索用户资料，处理启停、角色分配、密码重置和删除等高风险操作。</p>
      </div>
      <div class="toolbar-actions">
        <el-button :icon="'Refresh'" @click="load">刷新</el-button>
      </div>
    </div>
    <div class="filter-bar">
      <el-input v-model="filters.keyword" placeholder="用户名、昵称、手机号" clearable />
      <el-button type="primary" @click="search">查询</el-button>
    </div>
    <div class="desktop-table data-panel">
      <el-table v-loading="loading" :data="users" empty-text="暂无用户">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="nickname" label="昵称" min-width="140" />
        <el-table-column prop="phone" label="手机" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="sg-tag" :class="row.status === 'ACTIVE' ? 'sg-tag--success' : 'sg-tag--neutral'">
              {{ row.status === 'ACTIVE' ? '启用' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button size="small" type="primary" @click="openEdit(row)">编辑</el-button>
              <SgDropdown
                variant="chip"
                :options="userActionOptions(row)"
                placeholder="更多"
                aria-label="更多用户操作"
                @change="(value: string | number) => onUserAction(row, value)"
              />
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-pagination
      v-model:current-page="filters.pageNum"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="filters.pageSize"
      @current-change="load"
    />
    <el-dialog v-model="editDialogVisible" title="编辑用户" width="520px">
      <el-form label-width="88px">
        <el-form-item label="用户名" required>
          <el-input v-model.trim="editForm.username" maxlength="64" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model.trim="editForm.nickname" maxlength="64" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model.trim="editForm.phone" maxlength="11" placeholder="选填，11 位大陆手机号" data-test="user-phone" />
          <p v-if="editPhoneError" class="user-phone-error" role="alert" data-test="user-phone-error">{{ editPhoneError }}</p>
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model.trim="editForm.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="状态" required>
          <el-select v-model="editForm.status">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog
      v-model="roleDialogVisible"
      :title="selectedUser ? `分配角色：${selectedUser.username}` : '分配角色'"
      width="min(620px, calc(100% - 24px))"
    >
      <div v-loading="roleLoading" class="role-assignment-content">
        <p class="muted-text">选择该用户需要承担的角色。系统会在下一次导航时按服务器中的最新授权重新判断入口。</p>
        <el-checkbox-group v-model="selectedRoleIds" class="role-options" aria-label="角色选择">
          <el-checkbox v-for="role in roleOptions" :key="role.id" :value="role.id" class="role-option">
            <span class="role-option-main">
              <strong>{{ role.name }}</strong>
              <span>{{ roleScopeLabel(role.dataScope) }}</span>
            </span>
          </el-checkbox>
        </el-checkbox-group>
        <EmptyState v-if="!roleLoading && !roleOptions.length" compact title="暂无可分配角色" description="请联系系统管理员先创建角色" />
        <el-alert
          v-if="hasVenueAdmin"
          type="warning"
          :closable="false"
          show-icon
          title="场馆管理员还需要场馆范围授权"
          :description="venueScopeDescription"
        />
      </div>
      <template #footer>
        <el-button :disabled="roleSaving" @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" :disabled="roleLoading || !selectedRoleIds.length" @click="saveRoleAssignment">保存角色</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi } from '@/api/system'
import SgDropdown, { type SgDropdownOption } from '@/components/SgDropdown.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { RoleOption, SystemUser } from '@/api/system-types'
import { OPTIONAL_CHINA_MOBILE_MESSAGE, isValidOptionalChinaMobile } from '@/utils/phone'

const users = ref<SystemUser[]>([])
const total = ref(0)
const loading = ref(false)
const filters = reactive({ pageNum: 1, pageSize: 30, keyword: '' })
const editDialogVisible = ref(false)
const editSaving = ref(false)
const roleDialogVisible = ref(false)
const roleLoading = ref(false)
const roleSaving = ref(false)
const selectedUser = ref<SystemUser | null>(null)
const roleOptions = ref<RoleOption[]>([])
const selectedRoleIds = ref<number[]>([])
const assignedVenueIds = ref<number[]>([])
const editForm = reactive({
  id: 0,
  username: '',
  nickname: '',
  phone: '',
  email: '',
  status: 'ACTIVE'
})
/** 与服务端 `@OptionalChinaMobile` 对齐的提交前提示；服务端 422 文案在失败时另行展示。 */
const editPhoneError = ref('')
/** 操作列「编辑主钮+更多」两级（预览 §08 用户管理处方）：confirm 流程全部原样保留。 */
const secondaryAction = ref<string | number>()
const userActionOptions = (row: SystemUser): SgDropdownOption[] => [
  { value: 'toggle', label: row.status === 'ACTIVE' ? '禁用' : '启用' },
  { value: 'roles', label: '分配角色' },
  { value: 'venue', label: '场馆授权' },
  { value: 'password', label: '重置密码' },
  { value: 'delete', label: '删除' }
]
const onUserAction = (row: SystemUser, value: string | number) => {
  secondaryAction.value = undefined
  if (value === 'toggle') toggle(row)
  else if (value === 'roles') openRoleAssignment(row)
  else if (value === 'venue') assignVenueAccess(row)
  else if (value === 'password') resetPassword(row.id)
  else if (value === 'delete') remove(row)
}

const load = async () => {
  loading.value = true
  try {
    const page = await systemApi.users(filters)
    users.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}
const search = () => {
  filters.pageNum = 1
  load()
}
const openEdit = (user: SystemUser) => {
  editForm.id = user.id
  editForm.username = user.username
  editForm.nickname = user.nickname || ''
  editForm.phone = user.phone || ''
  editForm.email = user.email || ''
  editForm.status = user.status
  editPhoneError.value = ''
  editDialogVisible.value = true
}
const saveEdit = async () => {
  if (!editForm.username.trim()) {
    ElMessage.warning('用户名不能为空')
    return
  }
  if (!isValidOptionalChinaMobile(editForm.phone)) {
    editPhoneError.value = OPTIONAL_CHINA_MOBILE_MESSAGE
    ElMessage.warning(OPTIONAL_CHINA_MOBILE_MESSAGE)
    return
  }
  editPhoneError.value = ''
  editSaving.value = true
  try {
    const updated = await systemApi.updateUser(editForm.id, {
      username: editForm.username,
      nickname: editForm.nickname,
      phone: editForm.phone,
      email: editForm.email,
      status: editForm.status
    })
    users.value = users.value.map((user) => (user.id === updated.id ? updated : user))
    editDialogVisible.value = false
    ElMessage.success('用户资料已更新')
    await load()
  } catch (error) {
    const serverMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(serverMessage || '用户资料保存失败，请重试。')
  } finally {
    editSaving.value = false
  }
}
const toggle = async (user: SystemUser) => {
  await systemApi.updateUserStatus(user.id, user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
  ElMessage.success(user.status === 'ACTIVE' ? '用户已禁用' : '用户已启用')
  await load()
}
const roleScopeLabel = (scope: string) => ({
  SELF: '本人数据',
  VENUE: '授权场馆',
  ALL: '全局数据'
}[scope] || scope)
const hasVenueAdmin = computed(() => roleOptions.value.some((role) =>
  role.key === 'VENUE_ADMIN' && selectedRoleIds.value.includes(role.id)
))
const venueScopeDescription = computed(() => assignedVenueIds.value.length
  ? `当前已授权 ${assignedVenueIds.value.length} 个场馆。保存角色不会改变该范围，可继续使用“场馆授权”调整。`
  : '当前尚未授权任何场馆。保存角色后，请使用“场馆授权”配置可管理场馆。'
)
const readableError = (error: unknown, fallback: string) => error instanceof Error && error.message ? error.message : fallback
const openRoleAssignment = async (user: SystemUser) => {
  selectedUser.value = user
  roleDialogVisible.value = true
  roleLoading.value = true
  try {
    const [roles, roleIds, venueIds] = await Promise.all([
      systemApi.roles(),
      systemApi.userRoles(user.id),
      systemApi.venueAccess(user.id)
    ])
    roleOptions.value = roles
    selectedRoleIds.value = roleIds
    assignedVenueIds.value = venueIds
  } catch (error) {
    roleDialogVisible.value = false
    ElMessage.error(readableError(error, '角色信息加载失败，请重试'))
  } finally {
    roleLoading.value = false
  }
}
const saveRoleAssignment = async () => {
  const user = selectedUser.value
  if (!user || !selectedRoleIds.value.length) {
    ElMessage.warning('请至少选择一个角色')
    return
  }
  const roleNames = roleOptions.value
    .filter((role) => selectedRoleIds.value.includes(role.id))
    .map((role) => role.name)
  try {
    await ElMessageBox.confirm(
      `确认将用户 ${user.username} 的角色更新为：${roleNames.join('、')}？`,
      '确认角色分配',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '返回核对' }
    )
  } catch {
    return
  }
  const venueAdminSelected = hasVenueAdmin.value
  roleSaving.value = true
  try {
    await systemApi.assignRoles(user.id, selectedRoleIds.value)
    roleDialogVisible.value = false
    ElMessage.success('用户角色已更新')
    if (venueAdminSelected) {
      ElMessage.info(venueScopeDescription.value)
    }
    await load()
  } catch (error) {
    ElMessage.error(readableError(error, '角色分配失败，请重试'))
  } finally {
    roleSaving.value = false
  }
}
const assignVenueAccess = async (user: SystemUser) => {
  const currentVenueIds = await systemApi.venueAccess(user.id)
  const { value } = await ElMessageBox.prompt(
    `为 ${user.username} 配置可管理场馆。当前已授权 ${currentVenueIds.length} 个场馆；留空将撤销全部场馆授权。`,
    '场馆授权',
    {
      inputValue: currentVenueIds.join(','),
      inputPlaceholder: '输入场馆 ID，多个用逗号分隔',
      confirmButtonText: '保存授权',
      cancelButtonText: '取消'
    }
  )
  const normalized = value.trim()
  const venueIds = normalized ? normalized.split(',').map((item: string) => Number(item.trim())) : []
  if (venueIds.some((venueId) => !Number.isSafeInteger(venueId) || venueId <= 0)) {
    ElMessage.warning('场馆 ID 必须为正整数')
    return
  }
  await systemApi.updateVenueAccess(user.id, venueIds)
  ElMessage.success('场馆授权已更新')
  await load()
}
const resetPassword = async (id: number) => {
  const { value } = await ElMessageBox.prompt('请输入新密码，长度 6-64 位', '重置密码', {
    inputType: 'password',
    confirmButtonText: '确认重置',
    cancelButtonText: '取消'
  })
  await systemApi.resetUserPassword(id, value)
  await load()
}
const remove = async (user: SystemUser) => {
  try {
    await ElMessageBox.confirm(`确认删除用户 ${user.username}？删除后该账号不能再登录。`, '删除用户', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
    await systemApi.deleteUser(user.id)
    users.value = users.value.filter((item) => item.id !== user.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('用户已删除')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      ElMessage.info('已取消删除')
    }
  }
}
onMounted(load)
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.role-assignment-content {
  display: grid;
  gap: 16px;
}

.role-options {
  display: grid;
  gap: 10px;
}

.role-option {
  width: 100%;
  min-height: 48px;
  margin-right: 0;
  padding: 10px 12px;
  border: 1px solid var(--sg-border);
  border-radius: var(--sg-radius-control);
}

.role-option-main {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}

.role-option-main span {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-sm);
}

.user-phone-error {
  margin: 4px 0 0;
  color: var(--sg-danger);
  font-size: var(--sg-fz-sm);
  line-height: 1.4;
}
</style>
