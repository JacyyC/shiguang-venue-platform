<template>
  <div ref="rootEl" class="sga-c" data-test="sga-platform-root">
    <header class="heading">
      <div class="eyebrow">平台范围 · 管理与运行</div>
      <h1>把权限交给合适的人</h1>
      <p class="intro">从账号、角色到场馆范围，核对每一次授权。参数与操作记录保留在同一工作目录中，便于追溯。</p>
    </header>
    <div class="desk">
      <nav class="domain-nav" aria-label="平台管理领域">
        <button
          v-for="item in PLATFORM_TABS"
          :key="item.key"
          type="button"
          :data-test="`tab-${item.key}`"
          :aria-current="tab === item.key"
          @click="switchTab(item.key)"
        >{{ item.name }}<small>{{ item.note }}</small></button>
      </nav>
      <div class="workspace">
        <div v-if="surface === 'loading'" class="empty" role="status" data-test="surface-loading">
          <h2>正在读取记录</h2>
          <p>内容尚未返回，请稍候。</p>
        </div>
        <div v-else-if="surface === 'error'" class="empty" role="alert" data-test="surface-error">
          <h2>记录暂时无法读取</h2>
          <p>这次读取没有成功，不能据此判断当前范围没有数据。</p>
          <button class="btn" type="button" @click="bootstrap">重新读取</button>
        </div>

        <!-- 平台看板 -->
        <template v-else-if="tab === 'dashboard'">
          <div class="context-line" data-test="dashboard-context">平台看板 / {{ todayText }} · 全平台统计</div>
          <div class="dashboard-ledger" data-test="dashboard-panel">
            <section>
              <h2>今日预约</h2>
              <p class="number">{{ dashboard?.todayReservations ?? '—' }} <small style="font-size:18px">笔</small></p>
              <p class="muted">全平台当日预约汇总</p>
              <div class="summary-line"><span>待审核</span><strong>{{ dashboard?.pendingReviews ?? '—' }} <small>项</small></strong></div>
              <div class="summary-line"><span>注册用户</span><strong>{{ dashboard?.totalUsers ?? '—' }} <small>人</small></strong></div>
              <div class="summary-line"><span>收入摘要</span><strong>¥ {{ money(dashboard?.revenue) }}</strong></div>
              <div class="summary-line"><span>占用率</span><strong>{{ dashboard ? Number(dashboard.occupancyRate).toFixed(1) : '—' }}%</strong></div>
              <p class="label-note">数值来自服务端当日统计；结算与资金核对见财务对账入口。</p>
            </section>
            <section>
              <h2>从事项进入工作</h2>
              <a class="route-line" data-test="goto-providers" href="/system-admin/providers"><strong>渠道观测 →</strong><p>读取降级、失败与证据新鲜度。</p></a>
              <button class="route-line link-button" type="button" data-test="goto-logs" @click="switchTab('logs')"><strong>安全审计 →</strong><p>核对登录结果和后台操作记录。</p></button>
              <a class="route-line" href="/system-admin/notice-delivery"><strong>触达证据 →</strong><p>进入公告与通知的投递与分析。</p></a>
              <a class="route-line" href="/system-admin/reconciliation"><strong>财务差异复核 →</strong><p>既有财务入口保留，逐笔核对账实差异。</p></a>
            </section>
          </div>
        </template>

        <!-- 账号与授权 -->
        <template v-else-if="tab === 'users'">
          <div v-if="!canUserManage" class="empty" data-test="denied-users">
            <h2>当前没有查看权限</h2>
            <p>当前账号能够进入工作台，但没有账号管理权限。可从目录返回平台看板。</p>
          </div>
          <template v-else>
            <div class="context-line" data-test="users-context">账号与授权 / {{ users.length }} 个账号</div>
            <div class="people" data-test="users-panel">
            <aside class="people-list">
              <label for="user-search" class="label-note">查找账号或姓名</label>
              <input id="user-search" v-model="userQuery" type="search" placeholder="例如：林澄">
              <button
                v-for="user in filteredUsers"
                :key="user.id"
                class="person"
                type="button"
                :data-test="`user-${user.id}`"
                :aria-pressed="selectedUserId === user.id"
                @click="selectUser(user.id)"
              >
                <strong>{{ userDisplayName(user) }}</strong>
                <small>{{ roleLabelOf(user, roleOptions) }} · {{ userStatusOf(user) }}</small>
              </button>
              <p v-if="!filteredUsers.length" class="muted">{{ users.length ? '没有匹配的账号。' : '当前范围没有账号。' }}</p>
            </aside>
            <section v-if="selectedUser">
              <div class="spread">
                <div class="row">
                  <span class="avatar" aria-hidden="true">{{ userDisplayName(selectedUser).slice(0, 1) }}</span>
                  <div><h2>{{ userDisplayName(selectedUser) }}</h2><span class="muted">{{ selectedUser.username }}</span></div>
                </div>
                <span class="badge" :class="{ attention: selectedUser.status === 'DISABLED' }">{{ userStatusOf(selectedUser) }}</span>
              </div>
              <dl class="data-pairs">
                <div><dt>账号编号</dt><dd>#{{ selectedUser.id }}</dd></div>
                <div><dt>联系邮箱</dt><dd>{{ emailText(selectedUser.email) }}</dd></div>
                <div><dt>最近登录</dt><dd>{{ fullDateTime(selectedUser.lastLoginTime) }}</dd></div>
                <div><dt>注册时间</dt><dd>{{ fullDate(selectedUser.createTime) }}</dd></div>
              </dl>
              <div class="row">
                <button class="btn" type="button" data-test="edit-user" @click="openEditUser">编辑资料</button>
                <button class="btn" type="button" data-test="reset-password" @click="openResetPassword">重置密码</button>
                <button class="link-button" type="button" data-test="toggle-status" @click="openToggleStatus">{{ selectedUser.status === 'DISABLED' ? '恢复账号' : '停用账号' }}</button>
                <button class="link-button" type="button" data-test="delete-user" @click="openDeleteUser">删除账号</button>
              </div>
              <div class="detail-section">
                <div class="spread"><div><h3>角色赋予动作能力</h3><p class="muted">角色决定可以进入哪些领域、执行哪些操作。</p></div></div>
                <div class="row">
                  <label v-for="role in roleOptions" :key="role.id" class="checkline" style="border:0">
                    <input v-model="draftRoleIds" type="checkbox" :value="role.id" :data-test="`role-check-${role.key}`">
                    <span>{{ role.name }}</span>
                  </label>
                </div>
              </div>
              <div class="detail-section">
                <h3>场馆授权限定管理范围</h3>
                <p class="muted">具备场馆管理角色后，仍需授权具体场馆。平台角色与场馆范围分别核对。</p>
                <label v-for="venue in venueChoices" :key="venue.id" class="checkline">
                  <input v-model="draftVenueIds" type="checkbox" :value="venue.id" :data-test="`venue-check-${venue.id}`">
                  <span>{{ venue.name }}<small>{{ venue.note }}</small></span>
                </label>
                <p v-if="!draftVenueIds.length" class="notice">这个账号当前没有场馆授权。平台观测角色不会因此获得场馆管理范围。</p>
                <div class="savebar spread">
                  <span class="muted">保存前核对账号与授权范围</span>
                  <button class="btn primary" type="button" data-test="save-access" @click="openSaveAccess">核对授权变更</button>
                </div>
              </div>
            </section>
            </div>
          </template>
        </template>

        <!-- 角色与叶子权限 -->
        <template v-else-if="tab === 'roles'">
          <div v-if="!canRoleManage" class="empty" data-test="denied-roles">
            <h2>当前没有查看权限</h2>
            <p>当前账号能够进入工作台，但没有角色管理权限。可从目录返回平台看板。</p>
          </div>
          <template v-else>
            <div class="spread">
              <div><h2>角色与叶子权限</h2><p class="muted">工作台入口、查看与执行动作分别授予。</p></div>
              <button class="btn" type="button" data-test="create-role" @click="openCreateRole">创建动态角色</button>
            </div>
            <div class="people" data-test="roles-panel">
              <aside class="people-list">
                <button
                  v-for="role in managedRoles"
                  :key="role.id"
                  class="person role-list-item"
                  type="button"
                  :data-test="`managed-role-${role.id}`"
                  :aria-pressed="selectedRoleId === role.id"
                  @click="selectRole(role.id)"
                >
                  <strong>{{ role.name }}</strong>
                  <small>{{ roleListLabel(role) }}</small>
                </button>
                <p v-if="!managedRoles.length" class="muted">当前范围没有角色。</p>
              </aside>
              <section v-if="selectedManagedRole">
                <div class="spread">
                  <div><h2>{{ selectedManagedRole.name }}</h2><p class="muted">{{ selectedManagedRole.key }}</p></div>
                  <span class="badge">{{ roleBadge(selectedManagedRole) }}</span>
                </div>
                <p v-if="selectedManagedRole.builtIn" class="notice">内置角色的权限仅供核对，不能在这里修改。需要独立授权组合时，请创建动态角色。</p>
                <p v-else class="muted">勾选授予的叶子权限。具备入口不会自动选中此领域的管理或刷新动作。</p>
                <p class="label-note">权限清单来自服务端菜单登记全集；账号管理与角色管理是系统保留权限，不可委派给动态角色。进入系统后台仍需 SYSTEM_ADMIN 角色，授予叶子权限不会绕过入口角色约束。</p>
                <div class="permission-groups">
                  <section v-for="group in permissionGroups" :key="group.rootName">
                    <h3>{{ group.rootName }}</h3>
                    <label v-for="item in group.items" :key="item.id" class="checkline">
                      <input
                        v-model="draftPermissionIds"
                        type="checkbox"
                        :value="item.id"
                        :data-test="`perm-check-${item.id}`"
                        :disabled="selectedManagedRole.builtIn"
                      >
                      <span>{{ item.name }}<small>{{ item.permission }}</small></span>
                    </label>
                  </section>
                </div>
                <div v-if="!selectedManagedRole.builtIn" class="savebar spread">
                  <span class="muted">{{ savedPermissionCount }} 项已保存权限<span v-if="draftPermissionIds.length !== savedPermissionCount"> · {{ draftPermissionIds.length }} 项待保存</span></span>
                  <button class="btn primary" type="button" data-test="save-role" @click="openSaveRole">核对权限变更</button>
                </div>
              </section>
            </div>
          </template>
        </template>

        <!-- 平台参数 -->
        <template v-else-if="tab === 'configs'">
          <div v-if="!canConfigManage" class="empty" data-test="denied-configs">
            <h2>当前没有查看权限</h2>
            <p>当前账号能够进入工作台，但没有参数管理权限。可从目录返回平台看板。</p>
          </div>
          <div v-else data-test="configs-panel">
            <h2>平台参数</h2>
            <p class="intro">先阅读参数的用途，再修改具体数值。这里展示既有预约与支付参数，不提供环境部署或渠道生产开关。</p>
            <p v-if="!configs.length" class="empty">当前范围没有记录。</p>
            <section v-for="config in configs" :key="config.id" class="config-row">
              <div>
                <h3>{{ config.configName }}</h3>
                <p class="muted">{{ config.description || '—' }}</p>
                <code>{{ config.configKey }}</code>
              </div>
              <div class="config-value">{{ configValueText(config) }}</div>
              <button class="btn" type="button" :data-test="`config-edit-${config.configKey}`" @click="openConfigEdit(config)">修改</button>
            </section>
          </div>
        </template>

        <!-- 登录与操作日志 -->
        <template v-else>
          <div v-if="!canLogView" class="empty" data-test="denied-logs">
            <h2>当前没有查看权限</h2>
            <p>当前账号能够进入工作台，但没有日志查看权限。可从目录返回平台看板。</p>
          </div>
          <div v-else data-test="logs-panel">
            <h2>登录与操作日志</h2>
            <p class="intro">按发生时间读取证据。日志只有查看入口，不能在这里修改或删除。</p>
            <div class="subtabs">
              <button type="button" data-test="log-operation" :aria-pressed="logTab === 'operation'" @click="switchLogTab('operation')">操作记录</button>
              <button type="button" data-test="log-login" :aria-pressed="logTab === 'login'" @click="switchLogTab('login')">登录记录</button>
            </div>
            <template v-if="logTab === 'operation'">
              <article v-for="log in operationLogs" :key="`op-${log.id}`" class="log-row">
                <time>{{ logDay(log.createTime) }}<br>{{ hmTime(log.createTime) }}</time>
                <div>
                  <h3>{{ log.action || '—' }}</h3>
                  <p class="muted">{{ log.username || '—' }} · {{ log.module || '平台操作' }}</p>
                  <p v-if="log.errorMessage" class="muted accent">{{ log.errorMessage }}</p>
                  <button class="link-button" type="button" :data-test="`log-detail-${log.id}`" @click="openLogDetail('operation', log.id)">查看记录详情</button>
                </div>
                <span class="badge" :class="{ attention: logAttention(log.resultStatus) }">{{ logStatusLabel(log.resultStatus) }}</span>
              </article>
              <p v-if="!operationLogs.length" class="empty">当前范围没有记录。</p>
              <p v-else class="label-note">当前 {{ operationLogs.length }} 条记录 · 地址信息按脱敏形式展示</p>
            </template>
            <template v-else>
              <article v-for="log in loginLogs" :key="`login-${log.id}`" class="log-row">
                <time>{{ logDay(log.createTime) }}<br>{{ hmTime(log.createTime) }}</time>
                <div>
                  <h3>登录</h3>
                  <p class="muted">{{ log.username || '—' }} · {{ log.message || '—' }}</p>
                  <button class="link-button" type="button" :data-test="`log-detail-${log.id}`" @click="openLogDetail('login', log.id)">查看记录详情</button>
                </div>
                <span class="badge" :class="{ attention: logAttention(log.status) }">{{ logStatusLabel(log.status) }}</span>
              </article>
              <p v-if="!loginLogs.length" class="empty">当前范围没有记录。</p>
              <p v-else class="label-note">当前 {{ loginLogs.length }} 条记录 · 地址信息按脱敏形式展示</p>
            </template>
          </div>
        </template>
      </div>
    </div>

    <dialog ref="dialogEl" class="c-modal" aria-labelledby="c-dialog-title" @close="onDialogClose">
      <form v-if="dialog" @submit.prevent="confirmDialog">
        <h2 id="c-dialog-title">{{ dialog.title }}</h2>
        <template v-if="dialog.kind === 'edit-user'">
          <p class="muted">{{ selectedUser?.username }}</p>
          <div class="form-field"><label for="pf-nickname">显示名称</label><input id="pf-nickname" v-model="form.nickname" data-test="pf-nickname" maxlength="64"></div>
          <div class="form-field"><label for="pf-phone">联系电话 <span class="muted">选填，11 位大陆手机号</span></label><input id="pf-phone" v-model="form.phone" data-test="pf-phone" inputmode="numeric" maxlength="11" placeholder="13800138000" autocomplete="tel"></div>
          <div class="form-field"><label for="pf-email">联系邮箱</label><input id="pf-email" v-model="form.email" type="email" maxlength="128"></div>
        </template>
        <template v-else-if="dialog.kind === 'password'">
          <p class="muted">为账号 {{ selectedUser?.username }} 设置新密码。保存后请通过受控渠道告知本人。</p>
          <div class="form-field"><label for="pf-password">新密码</label><input id="pf-password" v-model="form.password" type="password" autocomplete="new-password" minlength="8" data-test="pf-password" required></div>
        </template>
        <template v-else-if="dialog.kind === 'status'">
          <p>确认对「{{ userDisplayName(selectedUser!) }}」执行此操作。账号状态与角色、场馆范围分别保存。</p>
        </template>
        <template v-else-if="dialog.kind === 'delete'">
          <p>此操作会从系统删除「{{ userDisplayName(selectedUser!) }}」并保留审计快照，删除后不可恢复。请输入 DELETE 确认。</p>
          <div class="form-field"><label for="pf-delete">确认文字</label><input id="pf-delete" v-model="form.confirmWord" autocomplete="off" data-test="pf-delete"></div>
        </template>
        <template v-else-if="dialog.kind === 'access'">
          <p>账号：<strong>{{ selectedUser ? userDisplayName(selectedUser) : '' }}</strong></p>
          <div class="detail-section"><h3>角色</h3><p>{{ accessSummaryText.roles }}</p><h3>场馆范围</h3><p>{{ accessSummaryText.venues }}</p></div>
          <p class="notice">角色与场馆授权是分别保存的动作。本页先核对两项内容再分别提交；其中一项失败时会明确提示已保存的部分。</p>
        </template>
        <template v-else-if="dialog.kind === 'create-role'">
          <div class="form-field"><label for="pf-role-name">角色名称</label><input id="pf-role-name" v-model="form.roleName" maxlength="30" placeholder="例如：渠道只读值班员" data-test="pf-role-name" required></div>
          <div class="form-field"><label for="pf-role-key">角色标识</label><input id="pf-role-key" v-model="form.roleKey" placeholder="例如：CHANNEL_OBSERVER（将自动转为大写）" data-test="pf-role-key" required></div>
        </template>
        <template v-else-if="dialog.kind === 'permissions'">
          <p>角色：{{ selectedManagedRole?.name }}</p>
          <p>将完整替换为以下 {{ draftPermissionIds.length }} 项权限：</p>
          <p>{{ permissionSummaryText || '不授予任何叶子权限' }}</p>
          <p class="notice">拥有 Provider 查看权限，不会自动获得刷新权限。</p>
        </template>
        <template v-else-if="dialog.kind === 'config'">
          <p class="muted">{{ editingConfig?.description || editingConfig?.configName }}</p>
          <p>当前值：{{ editingConfig ? configValueText(editingConfig) : '' }}</p>
          <div class="form-field">
            <label for="pf-config">新值</label>
            <select v-if="editingConfig && configIsBoolean(editingConfig)" id="pf-config" v-model="form.configValue" data-test="pf-config">
              <option value="false">关闭</option>
              <option value="true">开启</option>
            </select>
            <input v-else id="pf-config" v-model="form.configValue" type="number" min="0" step="1" data-test="pf-config" required>
          </div>
        </template>
        <template v-else-if="dialog.kind === 'log-detail'">
          <dl class="data-pairs">
            <div><dt>记录类型</dt><dd>{{ dialog.logKind === 'login' ? '登录记录' : '操作记录' }}</dd></div>
            <div><dt>编号</dt><dd>#{{ dialog.logId }}</dd></div>
            <div><dt>地址</dt><dd>{{ dialog.logIp }}</dd></div>
            <div><dt>发生日期</dt><dd>{{ dialog.logTime }}</dd></div>
          </dl>
          <p class="muted">只展示审计摘要，不展示原始请求或完整个人信息。</p>
        </template>
        <p v-if="dialog.error" class="error-text" role="alert" data-test="dialog-error">{{ dialog.error }}</p>
        <div class="row modal-actions">
          <button class="btn" type="button" data-test="dialog-cancel" @click="closeDialog">取消</button>
          <button
            v-if="dialog.submit"
            class="btn primary"
            type="submit"
            :disabled="dialog.busy"
            :data-test="`confirm-${dialog.kind}`"
          >
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
          <button v-else class="btn primary" type="button" data-test="dialog-close-detail" @click="closeDialog">关闭详情</button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { systemApi } from '@/api/system'
import { venueApi } from '@/api/venue'
import { statisticsApi, type DashboardSummary } from '@/api/statistics'
import type { LoginLog, ManagedRole, OperationLog, PermissionMenu, RoleOption, SystemConfig, SystemUser } from '@/api/system-types'
import type { VenueListItem } from '@/api/types'
import { ISOLATED_REQUEST as isolated, money } from '../adminShared'
import { OPTIONAL_CHINA_MOBILE_MESSAGE, isValidOptionalChinaMobile } from '@/utils/phone'
import {
  PLATFORM_TABS,
  accessSummary,
  configIsBoolean,
  configValueText,
  emailText,
  fullDate,
  fullDateTime,
  groupPermissions,
  hmTime,
  initialTabFromRoute,
  isoDateTime,
  logAttention,
  logDay,
  logStatusLabel,
  maskIp,
  matchUserQuery,
  readableError,
  roleBadge,
  roleLabelOf,
  roleListLabel,
  userDisplayName,
  userStatusOf,
  venueOptions,
  type PermissionGroup,
  type PlatformTab
} from './platformShared'

type SurfaceState = 'loading' | 'ready' | 'error'

type DialogKind = 'edit-user' | 'password' | 'status' | 'delete' | 'access' | 'create-role' | 'permissions' | 'config' | 'log-detail'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  logId: number | null
  logKind: 'operation' | 'login'
  logIp: string
  logTime: string
}

const route = useRoute()
const auth = useAuthStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)

const canUserManage = computed(() => auth.hasPermission('system:user:manage'))
const canRoleManage = computed(() => auth.hasPermission('system:role:manage'))
const canConfigManage = computed(() => auth.hasPermission('system:config:manage'))
const canLogView = computed(() => auth.hasPermission('system:log:view'))

const tab = ref<PlatformTab>(initialTabFromRoute(route?.name, route?.hash ?? ''))
const surface = ref<SurfaceState>('loading')
const dashboard = ref<DashboardSummary | null>(null)
const users = ref<SystemUser[]>([])
const roleOptions = ref<RoleOption[]>([])
const venues = ref<VenueListItem[]>([])
const managedRoles = ref<ManagedRole[]>([])
const permissionMenus = ref<PermissionMenu[]>([])
const configs = ref<SystemConfig[]>([])
const operationLogs = ref<OperationLog[]>([])
const loginLogs = ref<LoginLog[]>([])
const logTab = ref<'operation' | 'login'>('operation')
const userQuery = ref('')
const selectedUserId = ref<number | null>(null)
const selectedRoleId = ref<number | null>(null)
const draftRoleIds = ref<number[]>([])
const draftVenueIds = ref<number[]>([])
const draftPermissionIds = ref<number[]>([])
const savedPermissionCount = ref(0)
const editingConfig = ref<SystemConfig | null>(null)

const filteredUsers = computed(() => users.value.filter((user) => matchUserQuery(user, userQuery.value.trim())))
const selectedUser = computed(() => users.value.find((user) => user.id === selectedUserId.value) ?? null)
const selectedManagedRole = computed(() => managedRoles.value.find((role) => role.id === selectedRoleId.value) ?? null)
const permissionGroups = computed<PermissionGroup[]>(() => groupPermissions(permissionMenus.value))
const venueChoices = computed(() => venueOptions(venues.value))
const todayText = computed(() => fullDate(new Date().toISOString()))
const permissionSummaryText = computed(() =>
  permissionGroups.value
    .flatMap((group) => group.items)
    .filter((item) => draftPermissionIds.value.includes(item.id))
    .map((item) => item.name)
    .join('、')
)
const accessSummaryText = computed(() => {
  const roleNames = draftRoleIds.value
    .map((id) => roleOptions.value.find((role) => role.id === id)?.name ?? '')
    .filter(Boolean)
  const venueNames = draftVenueIds.value
    .map((id) => venues.value.find((venue) => venue.id === id)?.name ?? '')
    .filter(Boolean)
  return accessSummary(roleNames, venueNames)
})

onMounted(() => {
  void bootstrap()
})

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  try {
    const pageParams = { pageNum: 1, pageSize: 100 }
    const [dash, userPage, roles, managedRoleList, permissionList, configList, ops, logins, venuePage] = await Promise.all([
      statisticsApi.dashboard({}, isolated),
      canUserManage.value ? systemApi.users({ ...pageParams }, isolated) : Promise.resolve({ records: [] as SystemUser[], total: 0, pageNum: 1, pageSize: 100 }),
      canUserManage.value ? systemApi.roles(isolated) : Promise.resolve([] as RoleOption[]),
      canRoleManage.value ? systemApi.managedRoles(isolated) : Promise.resolve([] as ManagedRole[]),
      canRoleManage.value ? systemApi.permissionMenus(isolated) : Promise.resolve([] as PermissionMenu[]),
      canConfigManage.value ? systemApi.configs(isolated) : Promise.resolve([] as SystemConfig[]),
      canLogView.value ? systemApi.operationLogs({ ...pageParams }, isolated) : Promise.resolve({ records: [], total: 0, pageNum: 1, pageSize: 100 }),
      canLogView.value ? systemApi.loginLogs({ ...pageParams }, isolated) : Promise.resolve({ records: [], total: 0, pageNum: 1, pageSize: 100 }),
      venueApi.list({ ...pageParams }, isolated)
    ])
    dashboard.value = dash
    users.value = userPage.records
    roleOptions.value = roles
    managedRoles.value = managedRoleList
    permissionMenus.value = permissionList
    configs.value = configList
    operationLogs.value = ops.records
    loginLogs.value = logins.records
    venues.value = venuePage.records ?? []
    if (!selectedUserId.value) selectedUserId.value = users.value[0]?.id ?? null
    if (selectedUserId.value != null) await loadUserAccess(selectedUserId.value)
    if (!selectedRoleId.value) selectedRoleId.value = managedRoles.value[0]?.id ?? null
    if (selectedRoleId.value != null) await loadRolePermissions(selectedRoleId.value)
    surface.value = 'ready'
  } catch (error) {
    console.error('platform bootstrap failed', error)
    surface.value = 'error'
  }
}

async function loadUserAccess(userId: number): Promise<void> {
  try {
    const [roleIds, venueIds] = await Promise.all([
      systemApi.userRoles(userId, isolated),
      systemApi.venueAccess(userId, isolated)
    ])
    draftRoleIds.value = [...(roleIds ?? [])]
    draftVenueIds.value = [...(venueIds ?? [])]
  } catch {
    draftRoleIds.value = []
    draftVenueIds.value = []
  }
}

async function loadRolePermissions(roleId: number): Promise<void> {
  try {
    const ids = await systemApi.rolePermissions(roleId, isolated)
    draftPermissionIds.value = [...(ids ?? [])]
    savedPermissionCount.value = draftPermissionIds.value.length
  } catch {
    draftPermissionIds.value = []
    savedPermissionCount.value = 0
  }
}

function switchTab(next: PlatformTab): void {
  tab.value = next
  if (route && route.hash !== `#${next}`) void nextTick(() => history.replaceState(null, '', `#${next}`))
  void nextTick(() => {
    ;(rootEl.value?.querySelector(`[data-test="tab-${next}"]`) as HTMLElement | null)?.focus()
  })
}

function switchLogTab(next: 'operation' | 'login'): void {
  logTab.value = next
  void nextTick(() => {
    ;(rootEl.value?.querySelector(`[data-test="log-${next}"]`) as HTMLElement | null)?.focus()
  })
}

async function selectUser(userId: number): Promise<void> {
  selectedUserId.value = userId
  await loadUserAccess(userId)
}

async function selectRole(roleId: number): Promise<void> {
  selectedRoleId.value = roleId
  await loadRolePermissions(roleId)
}

// ---------- 弹层 ----------

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null
const form = reactive({
  nickname: '',
  phone: '',
  email: '',
  password: '',
  confirmWord: '',
  roleName: '',
  roleKey: '',
  configValue: ''
})

function openDialog(next: DialogState, trigger: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement as HTMLElement | null)
  dialog.value = next
  form.nickname = selectedUser.value?.nickname ?? ''
  form.phone = selectedUser.value?.phone ?? ''
  form.email = selectedUser.value?.email ?? ''
  form.password = ''
  form.confirmWord = ''
  form.roleName = ''
  form.roleKey = ''
  form.configValue = editingConfig.value?.configValue ?? ''
  void nextTick(() => dialogEl.value?.showModal())
}

function closeDialog(): void {
  dialogEl.value?.close()
}

function onDialogClose(): void {
  const trigger = lastTrigger
  dialog.value = null
  lastTrigger = null
  if (trigger?.isConnected) trigger.focus()
}

function openEditUser(event?: Event): void {
  openDialog({ kind: 'edit-user', title: '编辑账号资料', submit: '保存资料', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openResetPassword(event?: Event): void {
  openDialog({ kind: 'password', title: '重置账号密码', submit: '确认重置', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openToggleStatus(event?: Event): void {
  const disabling = selectedUser.value?.status !== 'DISABLED'
  openDialog({ kind: 'status', title: disabling ? '停用账号' : '恢复账号', submit: '确认', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openDeleteUser(event?: Event): void {
  openDialog({ kind: 'delete', title: '删除账号', submit: '删除账号', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openSaveAccess(event?: Event): void {
  openDialog({ kind: 'access', title: '核对账号授权', submit: '保存授权', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openCreateRole(event?: Event): void {
  openDialog({ kind: 'create-role', title: '创建动态角色', submit: '创建角色', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openSaveRole(event?: Event): void {
  openDialog({ kind: 'permissions', title: '核对叶子权限', submit: '保存权限', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openConfigEdit(config: SystemConfig, event?: Event): void {
  editingConfig.value = config
  openDialog({ kind: 'config', title: `修改${config.configName}`, submit: '保存参数', error: '', busy: false, logId: null, logKind: 'operation', logIp: '', logTime: '' }, event ?? null)
}

function openLogDetail(kind: 'operation' | 'login', logId: number, event?: Event): void {
  const log = kind === 'login'
    ? loginLogs.value.find((item) => item.id === logId)
    : operationLogs.value.find((item) => item.id === logId)
  openDialog({
    kind: 'log-detail',
    title: '日志记录详情',
    submit: '',
    error: '',
    busy: false,
    logId,
    logKind: kind,
    logIp: maskIp(log?.ip),
    logTime: isoDateTime(log?.createTime)
  }, event ?? null)
}

// ---------- 提交 ----------

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state || state.busy) return
  state.error = ''
  const localError = validate(state)
  if (localError) {
    state.error = localError
    return
  }
  state.busy = true
  try {
    await submitOf(state)
  } catch (error) {
    state.busy = false
    state.error = readableError(error, '这次提交没有成功，内容已保留。请重试。')
    return
  }
  state.busy = false
  closeDialog()
}

/** 提交前的本地校验（与 S04 资源面同一形状：失败时保留弹层与输入）。 */
function validate(state: DialogState): string | null {
  switch (state.kind) {
    case 'edit-user':
      if (!form.nickname.trim()) return '请输入显示名称。'
      return isValidOptionalChinaMobile(form.phone) ? null : OPTIONAL_CHINA_MOBILE_MESSAGE
    case 'password':
      return form.password.length >= 8 ? null : '请输入至少 8 位的新密码。'
    case 'delete':
      return form.confirmWord === 'DELETE' ? null : '请完整输入 DELETE。'
    case 'create-role':
      return form.roleName.trim() && form.roleKey.trim() ? null : '请填写名称与标识。'
    case 'config':
      return configInputText() ? null : '请输入参数值。'
    default:
      return null
  }
}

/** 数值输入的 v-model 值是 number，统一归一为字符串（服务端 config_value 存文本并自行解析）。 */
function configInputText(): string {
  return String(form.configValue ?? '').trim()
}

async function submitOf(state: DialogState): Promise<void> {
  const user = selectedUser.value
  switch (state.kind) {
    case 'edit-user': {
      if (!user) return
      await systemApi.updateUser(user.id, {
        username: user.username,
        nickname: form.nickname.trim(),
        phone: form.phone.trim() || undefined,
        email: form.email.trim() || undefined,
        status: user.status
      }, isolated)
      app.showToast('账号资料已更新')
      await bootstrap()
      return
    }
    case 'password': {
      if (!user) return
      await systemApi.resetUserPassword(user.id, form.password, isolated)
      app.showToast('密码已重置')
      return
    }
    case 'status': {
      if (!user) return
      const next = user.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
      await systemApi.updateUserStatus(user.id, next, isolated)
      app.showToast(next === 'DISABLED' ? '账号已停用' : '账号已恢复')
      await bootstrap()
      return
    }
    case 'delete': {
      if (!user) return
      await systemApi.deleteUser(user.id, isolated)
      app.showToast('账号已删除，审计快照保留')
      selectedUserId.value = null
      await bootstrap()
      return
    }
    case 'access': {
      if (!user) return
      // B2-T26：角色与场馆授权是两个独立端点两次提交；第二项失败时如实提示已生效的部分
      await systemApi.assignRoles(user.id, [...draftRoleIds.value], isolated)
      let partialFailure = ''
      try {
        await systemApi.updateVenueAccess(user.id, [...draftVenueIds.value], isolated)
        app.showToast('角色与场馆范围已保存')
      } catch (venueError) {
        partialFailure = `角色已保存，但场馆授权未能保存：${readableError(venueError, '请核对后重试')}`
      }
      await bootstrap()
      if (partialFailure) throw new Error(partialFailure)
      return
    }
    case 'create-role': {
      await systemApi.createManagedRole({ roleName: form.roleName.trim(), roleKey: form.roleKey.trim() }, isolated)
      app.showToast('动态角色已创建，请继续授予叶子权限')
      await bootstrap()
      return
    }
    case 'permissions': {
      if (!selectedManagedRole.value) return
      await systemApi.replaceRolePermissions(selectedManagedRole.value.id, [...draftPermissionIds.value], isolated)
      app.showToast('角色权限已更新')
      await bootstrap()
      return
    }
    case 'config': {
      if (!editingConfig.value) return
      await systemApi.updateConfig(editingConfig.value.configKey, configInputText(), isolated)
      app.showToast('参数值已更新')
      await bootstrap()
      return
    }
    case 'log-detail':
      return
  }
}
</script>
