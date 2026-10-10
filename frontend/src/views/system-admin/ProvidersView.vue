<template>
  <section class="admin-page provider-admin-page" :aria-busy="loading">
    <div class="page-head">
      <div class="page-head-main">
        <p class="sg-kicker">平台治理 · 外部观测</p>
        <h1 class="sg-display-l page-title">外部渠道观测</h1>
        <p class="section-desc">汇总天气、风险和本地指标渠道的健康状态，优先定位过期证据与失败调用。</p>
      </div>
      <div class="toolbar-actions">
        <div class="provider-tag-list" aria-label="渠道清单">
          <span
            v-for="provider in providerTags"
            :key="provider.key"
            class="sg-tag provider-tag"
            :class="sgTagTone(statusTagType(provider.status))"
          >
            {{ provider.label }}
          </span>
        </div>
        <el-tooltip content="刷新当前观测数据">
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新当前观测数据" @click="loadAll">刷新</el-button>
        </el-tooltip>
      </div>
    </div>

    <el-alert
      v-for="gap in dataGaps"
      :key="gap"
      class="provider-gap-alert"
      type="warning"
      :closable="false"
      :title="gap"
      show-icon
    />

    <el-form
      class="filter-bar provider-filter-bar"
      label-position="top"
      :model="filters"
      aria-label="渠道观测筛选"
      @submit.prevent="applyFilters"
    >
      <el-form-item label="调用渠道域">
        <el-select v-model="filters.providerDomain" class="provider-domain-select" clearable placeholder="全部域">
          <el-option v-for="domain in providerDomains" :key="domain" :label="domain" :value="domain" />
        </el-select>
        <small class="filter-scope">仅影响调用日志</small>
      </el-form-item>
      <el-form-item label="调用状态">
        <el-select v-model="filters.status" clearable placeholder="全部状态">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="超时" value="TIMEOUT" />
          <el-option label="已跳过" value="SKIPPED" />
          <el-option label="已降级" value="DEGRADED" />
        </el-select>
        <small class="filter-scope">仅影响调用日志</small>
      </el-form-item>
      <el-form-item label="调用来源">
        <el-select v-model="filters.sourceType" class="provider-source-select" clearable placeholder="全部来源">
          <el-option v-for="source in callSources" :key="source" :label="sourceTypeLabel(source)" :value="source" />
        </el-select>
        <small class="filter-scope">仅影响调用日志</small>
      </el-form-item>
      <el-form-item label="快照场馆编号">
        <el-input v-model.number="filters.venueId" clearable placeholder="输入场馆编号" />
        <small class="filter-scope">影响天气与风险快照</small>
      </el-form-item>
      <el-form-item label="运行指标键">
        <el-input v-model.trim="filters.metricKey" clearable placeholder="例如 redis_hit_rate" />
        <small class="filter-scope">仅影响运行指标</small>
      </el-form-item>
      <div class="filter-actions">
        <el-button type="primary" native-type="submit" :icon="Search" :loading="loading">查询</el-button>
      </div>
    </el-form>

    <section class="data-panel kpi-strip provider-kpi-strip" aria-label="渠道概览">
      <div class="provider-context-copy"><span>当前范围</span><strong>全部观测渠道</strong><small>{{ filters.venueId ? `场馆 ${filters.venueId}` : '全部场馆' }}</small></div>
      <div class="kpi-item"><span>渠道总数</span><strong><SgCountNumber v-if="overview && !overviewError && !overviewLoading" :value="providerTotal" /><template v-else>—</template><small v-if="overview && !overviewError && !overviewLoading">个</small></strong></div>
      <div class="kpi-item"><span>数据新鲜</span><strong><template v-if="overview && !overviewError && !overviewLoading">{{ freshProviderCount }}/{{ providerTotal }}</template><template v-else>—</template></strong></div>
      <div class="kpi-item kpi-card provider-failure-kpi"><span>异常调用</span><strong class="is-warning"><SgCountNumber v-if="!callsError && !callLoading" :value="failedCallCount" /><template v-else>—</template></strong></div>
      <div class="kpi-item"><span>观测位置</span><strong><SgCountNumber v-if="!locationsError && !locationLoading" :value="locations.length" /><template v-else>—</template><small v-if="!locationsError && !locationLoading">处</small></strong></div>
    </section>

    <section class="content-grid provider-status-grid">
      <div class="desktop-table data-panel provider-section-panel">
        <div class="panel-title">
          <h2>渠道状态</h2>
          <span class="muted-text">{{ overviewError || overviewLoading ? '—' : providerTotal }} 个</span>
        </div>
        <el-alert
          v-if="overviewError"
          class="provider-section-error provider-overview-error"
          type="error"
          :closable="false"
          :title="overviewError"
          show-icon
        >
          <template #default><el-button size="small" @click="loadOverview">重试</el-button></template>
        </el-alert>
        <el-alert
          v-else-if="overviewLoading"
          title="正在加载渠道状态"
          type="info"
          :closable="false"
          show-icon
        />
        <EmptyState v-else-if="!providers.length" compact title="暂无渠道" />
        <el-table v-else class="provider-status-table" :data="providers">
          <el-table-column type="expand">
            <template #default="{ row }">
              <EvidenceDetail :items="[
                  { label: '内部渠道标识', value: row.providerName, mono: true },
                  { label: '配置版本', value: row.configVersion || '-', mono: true },
                  { label: '新鲜度截止', value: row.freshnessDeadlineAt || '-', mono: true }
                ]" />
            </template>
          </el-table-column>
          <el-table-column prop="displayName" label="名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="providerDomain" label="渠道域" min-width="110" show-overflow-tooltip />
          <el-table-column label="启用状态" width="110">
            <template #default="{ row }">
              <StatusTag :value="row.enabled ? 'ENABLED' : 'DISABLED'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :value="row.status || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column label="数据新鲜度" min-width="130">
            <template #default="{ row }">
              <StatusTag :value="row.freshnessStatus || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column label="证据年龄" min-width="110">
            <template #default="{ row }">{{ formatSeconds(row.evidenceAgeSeconds) }}</template>
          </el-table-column>
          <el-table-column prop="lastSuccessAt" label="最近成功" min-width="170" />
          <el-table-column prop="lastFailureAt" label="最近失败" min-width="170" />
          <el-table-column prop="lastFailureCode" label="最近失败码" min-width="150" show-overflow-tooltip />
          <el-table-column prop="lastFailureMessage" label="最近失败消息" min-width="220" show-overflow-tooltip />
          <el-table-column label="连续失败" width="110">
            <template #default="{ row }">{{ row.consecutiveFailures || 0 }}</template>
          </el-table-column>
        </el-table>
      </div>

      <div class="data-panel location-form-panel provider-section-panel">
        <div class="panel-title">
          <h2>场馆观测位置</h2>
          <span class="muted-text">天气刷新依赖经纬度</span>
        </div>
        <el-form v-if="canRefreshProviders" class="location-form" label-position="top" :model="locationForm" @submit.prevent="saveLocation">
          <div class="location-form-grid">
            <el-form-item label="场馆编号" required>
              <el-input v-model.number="locationForm.venueId" clearable placeholder="输入场馆编号" />
            </el-form-item>
            <el-form-item label="位置标签" required>
              <el-input v-model.trim="locationForm.locationLabel" clearable placeholder="例如 主场馆" />
            </el-form-item>
            <el-form-item label="纬度" required>
              <el-input v-model.number="locationForm.latitude" clearable placeholder="31.2304" />
            </el-form-item>
            <el-form-item label="经度" required>
              <el-input v-model.number="locationForm.longitude" clearable placeholder="121.4737" />
            </el-form-item>
            <el-form-item label="区域码">
              <el-input v-model.trim="locationForm.areaCode" clearable placeholder="310000" />
            </el-form-item>
            <el-form-item label="时区">
              <el-input v-model.trim="locationForm.timezone" clearable placeholder="Asia/Shanghai" />
            </el-form-item>
          </div>
          <div class="form-actions">
            <el-button native-type="submit" type="primary" :icon="LocationFilled" :loading="savingLocation">保存位置</el-button>
          </div>
        </el-form>
      </div>
    </section>

    <section class="content-grid provider-snapshot-grid">
      <div class="desktop-table data-panel provider-section-panel">
        <div class="panel-title">
          <h2>天气快照</h2>
          <el-tooltip content="按当前场馆刷新天气快照">
            <el-button
              v-if="canRefreshProviders"
              circle
              :icon="Refresh"
              :loading="refreshingWeather"
              aria-label="刷新天气快照"
              @click="refreshWeather"
            />
          </el-tooltip>
        </div>
        <el-alert
          v-if="weatherError"
          class="provider-section-error provider-weather-error"
          type="error"
          :closable="false"
          :title="weatherError"
          show-icon
        >
          <template #default><el-button size="small" @click="loadWeatherSnapshots">重试</el-button></template>
        </el-alert>
        <el-alert
          v-else-if="weatherLoading"
          title="正在加载天气快照"
          type="info"
          :closable="false"
          show-icon
        />
        <EmptyState v-else-if="!weatherSnapshots.length" compact title="暂无天气快照" />
        <el-table v-else :data="weatherSnapshots">
          <el-table-column type="expand"><template #default="{ row }"><EvidenceDetail :items="[
                  { label: '快照号', value: row.snapshotNo, mono: true },
                  { label: '内部渠道标识', value: row.providerName, mono: true }
                ]" /></template></el-table-column>
          <el-table-column prop="venueId" label="场馆" width="90" />
          <el-table-column label="渠道" min-width="140"><template #default="{ row }">{{ providerDisplayName(row.providerName) }}</template></el-table-column>
          <el-table-column label="温度" width="100">
            <template #default="{ row }">{{ formatMetric(row.temperatureC, '°C') }}</template>
          </el-table-column>
          <el-table-column label="降雨" width="100">
            <template #default="{ row }">{{ formatMetric(row.precipitationMm, 'mm') }}</template>
          </el-table-column>
          <el-table-column label="风速" width="100">
            <template #default="{ row }">{{ formatMetric(row.windSpeed, 'm/s') }}</template>
          </el-table-column>
          <el-table-column label="告警" width="110">
            <template #default="{ row }">
              <StatusTag :value="row.alertLevel || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column prop="summaryText" label="摘要" min-width="180" show-overflow-tooltip />
          <el-table-column prop="observedAt" label="观测时间" min-width="170" />
          <el-table-column prop="expiresAt" label="过期时间" min-width="170" />
        </el-table>
      </div>

      <div class="desktop-table data-panel provider-section-panel">
        <div class="panel-title">
          <h2>风险快照</h2>
          <el-tooltip content="刷新本地风险规则快照">
            <el-button
              v-if="canRefreshProviders"
              circle
              :icon="Refresh"
              :loading="refreshingRisk"
              aria-label="刷新风险快照"
              @click="refreshRisk"
            />
          </el-tooltip>
        </div>
        <el-alert
          v-if="riskError"
          class="provider-section-error provider-risk-error"
          type="error"
          :closable="false"
          :title="riskError"
          show-icon
        >
          <template #default><el-button size="small" @click="loadRiskSnapshots">重试</el-button></template>
        </el-alert>
        <el-alert
          v-else-if="riskLoading"
          title="正在加载风险快照"
          type="info"
          :closable="false"
          show-icon
        />
        <EmptyState v-else-if="!riskSnapshots.length" compact title="暂无风险快照" />
        <el-table v-else :data="riskSnapshots">
          <el-table-column type="expand"><template #default="{ row }"><EvidenceDetail :items="[
                  { label: '快照号', value: row.snapshotNo, mono: true },
                  { label: '内部渠道标识', value: row.providerName, mono: true }
                ]" /></template></el-table-column>
          <el-table-column label="渠道" min-width="180"><template #default="{ row }">{{ providerDisplayName(row.providerName) }}</template></el-table-column>
          <el-table-column label="风险级别" width="120">
            <template #default="{ row }">
              <StatusTag :value="row.riskLevel || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column label="分数" width="100">
            <template #default="{ row }">{{ formatNumber(row.riskScore) }}</template>
          </el-table-column>
          <el-table-column label="登录失败" width="110">
            <template #default="{ row }">{{ row.failedLoginCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="支付失败" width="110">
            <template #default="{ row }">{{ row.paymentFailureCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="公告失败" width="110">
            <template #default="{ row }">{{ row.noticeFailureCount || 0 }}</template>
          </el-table-column>
          <el-table-column label="投诉计数" width="120">
            <template #default="{ row }">{{ row.openComplaintCount || 0 }} / {{ row.severeComplaintCount || 0 }}</template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" min-width="170" />
        </el-table>
      </div>
    </section>

    <section class="content-grid provider-snapshot-grid">
      <div class="desktop-table data-panel provider-section-panel">
        <div class="panel-title">
          <h2>运行指标</h2>
          <el-tooltip content="刷新本地运行指标快照">
            <el-button
              v-if="canRefreshProviders"
              circle
              :icon="Refresh"
              :loading="refreshingMetrics"
              aria-label="刷新运行指标"
              @click="refreshMetrics"
            />
          </el-tooltip>
        </div>
        <el-alert
          v-if="metricsError"
          class="provider-section-error provider-metrics-error"
          type="error"
          :closable="false"
          :title="metricsError"
          show-icon
        >
          <template #default><el-button size="small" @click="loadMetricSnapshots">重试</el-button></template>
        </el-alert>
        <el-alert
          v-else-if="metricLoading"
          title="正在加载运行指标"
          type="info"
          :closable="false"
          show-icon
        />
        <EmptyState v-else-if="!metricSnapshots.length" compact title="暂无运行指标" />
        <el-table v-else :data="metricSnapshots">
          <el-table-column type="expand"><template #default="{ row }"><EvidenceDetail :items="[
                  { label: '快照号', value: row.snapshotNo, mono: true },
                  { label: '内部渠道标识', value: row.providerName, mono: true }
                ]" /></template></el-table-column>
          <el-table-column label="渠道" min-width="150"><template #default="{ row }">{{ providerDisplayName(row.providerName) }}</template></el-table-column>
          <el-table-column prop="metricKey" label="指标" min-width="160" />
          <el-table-column label="值" width="110">
            <template #default="{ row }">{{ formatMetric(row.metricValue, row.metricUnit) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :value="row.status || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column prop="observedAt" label="观测时间" min-width="170" />
          <el-table-column prop="expiresAt" label="过期时间" min-width="170" />
        </el-table>
      </div>

      <div class="desktop-table data-panel provider-section-panel">
        <div class="panel-title">
          <h2>调用日志</h2>
          <span class="muted-text">{{ callsError || callLoading ? '—' : callTotal }} 条</span>
        </div>
        <el-alert
          v-if="callsError"
          class="provider-section-error provider-calls-error"
          type="error"
          :closable="false"
          :title="callsError"
          show-icon
        >
          <template #default><el-button size="small" @click="loadCalls">重试</el-button></template>
        </el-alert>
        <el-alert
          v-else-if="callLoading"
          title="正在加载调用日志"
          type="info"
          :closable="false"
          show-icon
        />
        <EmptyState v-else-if="!callLogs.length" compact title="暂无调用日志" />
        <el-table v-else class="provider-call-log-table" :data="callLogs">
          <el-table-column type="expand"><template #default="{ row }"><EvidenceDetail :items="[
                  { label: '内部渠道标识', value: row.providerName, mono: true },
                  { label: '快照类型', value: row.snapshotType || '-', mono: true },
                  { label: '配置版本', value: row.configVersion || '-', mono: true }
                ]" /></template></el-table-column>
          <el-table-column prop="providerDomain" label="渠道域" width="110" />
          <el-table-column label="渠道" min-width="150"><template #default="{ row }">{{ providerDisplayName(row.providerName) }}</template></el-table-column>
          <el-table-column prop="operationType" label="操作" min-width="150" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <StatusTag :value="row.status || 'UNKNOWN'" kind="provider" />
            </template>
          </el-table-column>
          <el-table-column label="来源" width="120"><template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template></el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ row.durationMs ?? '-' }}ms</template>
          </el-table-column>
          <el-table-column prop="failureCode" label="失败码" min-width="150" show-overflow-tooltip />
          <el-table-column prop="occurredAt" label="调用时间" min-width="170" />
        </el-table>
        <el-pagination
          v-if="!callsError && !callLoading && callTotal"
          v-model:current-page="callPage.pageNum"
          class="section-pagination"
          layout="prev, pager, next, total"
          :total="callTotal"
          :page-size="callPage.pageSize"
          @current-change="loadCalls"
        />
      </div>
    </section>

    <div class="desktop-table data-panel provider-section-panel">
      <div class="panel-title">
        <h2>场馆观测位置</h2>
        <span class="muted-text">{{ locationsError || locationLoading ? '—' : locations.length }} 条</span>
      </div>
      <el-alert
        v-if="locationsError"
        class="provider-section-error provider-locations-error"
        type="error"
        :closable="false"
        :title="locationsError"
        show-icon
      >
        <template #default><el-button size="small" @click="loadLocations">重试</el-button></template>
      </el-alert>
      <el-alert
        v-else-if="locationLoading"
        title="正在加载观测位置"
        type="info"
        :closable="false"
        show-icon
      />
      <EmptyState v-else-if="!locations.length" compact title="暂无观测位置" />
      <el-table v-else :data="locations">
        <el-table-column prop="venueId" label="场馆 ID" width="110" />
        <el-table-column prop="locationLabel" label="位置标签" min-width="180" show-overflow-tooltip />
        <el-table-column prop="areaCode" label="区域码" width="110" />
        <el-table-column label="纬度" width="120">
          <template #default="{ row }">{{ formatNumber(row.latitude, 4) }}</template>
        </el-table-column>
        <el-table-column label="经度" width="120">
          <template #default="{ row }">{{ formatNumber(row.longitude, 4) }}</template>
        </el-table-column>
        <el-table-column prop="timezone" label="时区" min-width="150" />
        <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :value="row.status || 'UNKNOWN'" kind="provider" /></template></el-table-column>
        <el-table-column label="来源" width="110"><template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template></el-table-column>
        <el-table-column prop="updateTime" label="更新时间" min-width="170" />
        <el-table-column v-if="canRefreshProviders" label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="编辑观测位置">
              <el-button circle :icon="Edit" aria-label="编辑观测位置" @click="editLocation(row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<script setup lang="ts">
import EvidenceDetail from '@/components/EvidenceDetail.vue'
import SgCountNumber from '@/components/SgCountNumber.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit, LocationFilled, Refresh, Search } from '@element-plus/icons-vue'
import {
  providerApi,
  type OpsMetricSnapshotResponse,
  type ProviderCallLog,
  type ProviderCallSource,
  type ProviderOverviewResponse,
  type RiskSnapshotResponse,
  type VenueObservationLocationResponse,
  type WeatherSnapshotResponse
} from '@/api/provider'
import { useAuthStore } from '@/stores/auth'
import EmptyState from '@/components/EmptyState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { channelLabel, sgTagTone, sourceTypeLabel } from '@/utils/presentation'

type TagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

const overview = ref<ProviderOverviewResponse | null>(null)
const callLogs = ref<ProviderCallLog[]>([])
const weatherSnapshots = ref<WeatherSnapshotResponse[]>([])
const riskSnapshots = ref<RiskSnapshotResponse[]>([])
const metricSnapshots = ref<OpsMetricSnapshotResponse[]>([])
const locations = ref<VenueObservationLocationResponse[]>([])
const auth = useAuthStore()

const callTotal = ref(0)
const loading = ref(false)
const overviewLoading = ref(false)
const callLoading = ref(false)
const weatherLoading = ref(false)
const riskLoading = ref(false)
const metricLoading = ref(false)
const locationLoading = ref(false)
const overviewError = ref('')
const callsError = ref('')
const weatherError = ref('')
const riskError = ref('')
const metricsError = ref('')
const locationsError = ref('')
const refreshingWeather = ref(false)
const refreshingRisk = ref(false)
const refreshingMetrics = ref(false)
const savingLocation = ref(false)

const filters = reactive({
  providerDomain: '',
  status: '',
  sourceType: '' as ProviderCallSource | '',
  venueId: undefined as number | undefined,
  metricKey: ''
})

const callSources: ProviderCallSource[] = ['MANUAL', 'SCHEDULED', 'SYSTEM']

const callPage = reactive({ pageNum: 1, pageSize: 20 })
const weatherPage = reactive({ pageNum: 1, pageSize: 20 })
const riskPage = reactive({ pageNum: 1, pageSize: 20 })
const metricPage = reactive({ pageNum: 1, pageSize: 20 })

const locationForm = reactive({
  venueId: undefined as number | undefined,
  latitude: undefined as number | undefined,
  longitude: undefined as number | undefined,
  locationLabel: '',
  areaCode: '',
  timezone: 'Asia/Shanghai'
})

const providers = computed(() => overview.value?.providers || [])
const providerTags = computed(() =>
  providers.value.map((provider) => ({
    key: provider.providerKey,
    label: provider.displayName,
    status: provider.status
  }))
)
const providerDisplayName = (providerName?: string) => providers.value.find(
  (provider) => provider.providerName === providerName
)?.displayName || channelLabel(providerName)
const providerDomains = computed(() =>
  [...new Set(providers.value.map((provider) => provider.providerDomain).filter(Boolean))].sort()
)
const dataGaps = computed(() => overview.value?.dataGaps || [])
const providerTotal = computed(() => providers.value.length)
const staleProviderCount = computed(() =>
  providers.value.filter((provider) => provider.freshnessStatus && provider.freshnessStatus !== 'FRESH').length
)
const freshProviderCount = computed(() =>
  providers.value.filter((provider) => provider.freshnessStatus === 'FRESH').length
)
const failedCallCount = computed(() =>
  callLogs.value.filter((call) => ['FAILED', 'TIMEOUT', 'DEGRADED'].includes(call.status || '')).length
)
const canRefreshProviders = computed(() => auth.hasPermission('provider:refresh'))

const statusMap: Record<string, TagType> = {
  ACTIVE: 'success',
  SUCCESS: 'success',
  FRESH: 'success',
  OK: 'success',
  LOW: 'success',
  DEV: 'primary',
  DEGRADED: 'warning',
  STALE: 'warning',
  SKIPPED: 'warning',
  MEDIUM: 'warning',
  PENDING: 'primary',
  FAILED: 'danger',
  TIMEOUT: 'danger',
  EXPIRED: 'danger',
  HIGH: 'danger',
  CRITICAL: 'danger'
}

const statusTagType = (status?: string): TagType => statusMap[status || ''] || 'info'

const enabledLabel = (enabled?: boolean) => {
  if (enabled === true) return '已启用'
  if (enabled === false) return '已停用'
  return '-'
}

const enabledTagType = (enabled?: boolean): TagType => enabled === true ? 'success' : 'info'

const formatNumber = (value?: number, digits = 2) => {
  if (value === undefined || value === null || Number.isNaN(Number(value))) return '-'
  return Number(value).toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: digits
  })
}

const formatMetric = (value?: number, unit?: string) => {
  const formatted = formatNumber(value, 4)
  return formatted === '-' ? '-' : `${formatted}${unit ? ` ${unit}` : ''}`
}

const formatSeconds = (value?: number) => {
  const formatted = formatNumber(value, 0)
  return formatted === '-' ? '-' : `${formatted} 秒`
}

const compactParams = (params: Record<string, unknown>) =>
  Object.fromEntries(Object.entries(params).filter(([, value]) => value !== '' && value !== undefined && value !== null))

const venueParams = () => compactParams({ venueId: filters.venueId })
const callParams = () => compactParams({
  providerDomain: filters.providerDomain,
  status: filters.status,
  sourceType: filters.sourceType
})
const metricParams = () => compactParams({ metricKey: filters.metricKey })

const readableError = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message
  return fallback
}

const loadOverview = async () => {
  overviewLoading.value = true
  overviewError.value = ''
  try {
    overview.value = await providerApi.overview()
    overviewError.value = ''
  } catch (error) {
    overview.value = null
    overviewError.value = readableError(error, '渠道状态加载失败')
  } finally {
    overviewLoading.value = false
  }
}

const loadCalls = async () => {
  callLoading.value = true
  callsError.value = ''
  try {
    const page = await providerApi.calls({ ...callParams(), ...callPage })
    callLogs.value = page.records
    callTotal.value = page.total
    callsError.value = ''
  } catch (error) {
    callLogs.value = []
    callTotal.value = 0
    callsError.value = readableError(error, '调用日志加载失败')
  } finally {
    callLoading.value = false
  }
}

const loadWeatherSnapshots = async () => {
  weatherLoading.value = true
  weatherError.value = ''
  try {
    const page = await providerApi.weatherSnapshots({ ...venueParams(), ...weatherPage })
    weatherSnapshots.value = page.records
    weatherError.value = ''
  } catch (error) {
    weatherSnapshots.value = []
    weatherError.value = readableError(error, '天气快照加载失败')
  } finally {
    weatherLoading.value = false
  }
}

const loadRiskSnapshots = async () => {
  riskLoading.value = true
  riskError.value = ''
  try {
    const page = await providerApi.riskSnapshots({ ...venueParams(), ...riskPage })
    riskSnapshots.value = page.records
    riskError.value = ''
  } catch (error) {
    riskSnapshots.value = []
    riskError.value = readableError(error, '风险快照加载失败')
  } finally {
    riskLoading.value = false
  }
}

const loadMetricSnapshots = async () => {
  metricLoading.value = true
  metricsError.value = ''
  try {
    const page = await providerApi.metricSnapshots({ ...metricParams(), ...metricPage })
    metricSnapshots.value = page.records
    metricsError.value = ''
  } catch (error) {
    metricSnapshots.value = []
    metricsError.value = readableError(error, '运行指标加载失败')
  } finally {
    metricLoading.value = false
  }
}

const loadLocations = async () => {
  locationLoading.value = true
  locationsError.value = ''
  try {
    locations.value = await providerApi.locations()
    if (!locationForm.venueId && locations.value.length) {
      editLocation(locations.value[0])
    }
    locationsError.value = ''
  } catch (error) {
    locations.value = []
    locationsError.value = readableError(error, '场馆观测位置加载失败')
  } finally {
    locationLoading.value = false
  }
}

const loadAll = async () => {
  loading.value = true
  try {
    await Promise.all([
      loadOverview(),
      loadCalls(),
      loadWeatherSnapshots(),
      loadRiskSnapshots(),
      loadMetricSnapshots(),
      loadLocations()
    ])
  } finally {
    loading.value = false
  }
}

const applyFilters = () => {
  callPage.pageNum = 1
  weatherPage.pageNum = 1
  riskPage.pageNum = 1
  metricPage.pageNum = 1
  loadAll()
}

const refreshVenueId = () => {
  const venueId = locationForm.venueId ?? filters.venueId ?? locations.value[0]?.venueId
  return typeof venueId === 'number' && Number.isFinite(venueId) ? venueId : undefined
}

const refreshWeather = async () => {
  if (!canRefreshProviders.value) {
    ElMessage.warning('缺少渠道刷新权限')
    return
  }
  const venueId = refreshVenueId()
  if (!venueId) {
    ElMessage.warning('请先选择或维护场馆观测位置')
    return
  }
  refreshingWeather.value = true
  try {
    await providerApi.refreshWeather(venueId)
    ElMessage.success('天气渠道已刷新')
    await Promise.all([loadOverview(), loadWeatherSnapshots(), loadCalls()])
  } catch (error) {
    ElMessage.error(readableError(error, '天气渠道刷新失败'))
  } finally {
    refreshingWeather.value = false
  }
}

const refreshRisk = async () => {
  if (!canRefreshProviders.value) {
    ElMessage.warning('缺少渠道刷新权限')
    return
  }
  refreshingRisk.value = true
  try {
    await providerApi.refreshRisk(refreshVenueId())
    ElMessage.success('风险渠道已刷新')
    await Promise.all([loadOverview(), loadRiskSnapshots(), loadCalls()])
  } catch (error) {
    ElMessage.error(readableError(error, '风险渠道刷新失败'))
  } finally {
    refreshingRisk.value = false
  }
}

const refreshMetrics = async () => {
  if (!canRefreshProviders.value) {
    ElMessage.warning('缺少渠道刷新权限')
    return
  }
  refreshingMetrics.value = true
  try {
    await providerApi.refreshMetrics()
    ElMessage.success('运行指标渠道已刷新')
    await Promise.all([loadOverview(), loadMetricSnapshots(), loadCalls()])
  } catch (error) {
    ElMessage.error(readableError(error, '运行指标渠道刷新失败'))
  } finally {
    refreshingMetrics.value = false
  }
}

const editLocation = (row: VenueObservationLocationResponse) => {
  locationForm.venueId = row.venueId
  locationForm.latitude = row.latitude
  locationForm.longitude = row.longitude
  locationForm.locationLabel = row.locationLabel || ''
  locationForm.areaCode = row.areaCode || ''
  locationForm.timezone = row.timezone || 'Asia/Shanghai'
}

const saveLocation = async () => {
  if (!canRefreshProviders.value) {
    ElMessage.warning('缺少渠道刷新权限')
    return
  }
  if (!locationForm.venueId || typeof locationForm.latitude !== 'number' || typeof locationForm.longitude !== 'number') {
    ElMessage.warning('场馆 ID、纬度和经度必填')
    return
  }
  if (!locationForm.locationLabel.trim()) {
    ElMessage.warning('位置标签必填')
    return
  }
  savingLocation.value = true
  try {
    await providerApi.saveLocation(locationForm.venueId, {
      latitude: locationForm.latitude,
      longitude: locationForm.longitude,
      locationLabel: locationForm.locationLabel.trim(),
      areaCode: locationForm.areaCode.trim() || undefined,
      timezone: locationForm.timezone.trim() || undefined
    })
    ElMessage.success('观测位置已保存')
    await Promise.all([loadLocations(), loadWeatherSnapshots()])
  } catch (error) {
    ElMessage.error(readableError(error, '观测位置保存失败'))
  } finally {
    savingLocation.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.sg-kicker {
  max-width: 420px;
}

.sg-display-l.page-title {
  margin: 12px 0 4px;
}

.provider-admin-page {
  max-width: 1440px;
}

.provider-filter-bar {
  grid-template-columns: repeat(4, minmax(150px, 1fr)) minmax(96px, auto);
}

.provider-filter-bar :deep(.el-form-item),
.location-form-grid :deep(.el-form-item) {
  margin-bottom: 0;
}

.filter-scope {
  display: block;
  width: 100%;
  margin-top: 5px;
  color: var(--sg-ink-subtle);
  font-size: var(--sg-fz-xs);
  line-height: 1.4;
}

.provider-section-panel {
  min-width: 0;
}

.provider-technical-detail {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 24px;
  margin: 0;
  padding: 12px 20px;
  background: var(--sg-surface-subtle);
}

.provider-technical-detail dt {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
}

.provider-technical-detail dd {
  margin: 3px 0 0;
  overflow-wrap: anywhere;
  font-family: var(--sg-font-mono);
  font-size: var(--sg-fz-sm);
}

.provider-section-panel :deep(.el-table),
.provider-section-panel :deep(.el-table__inner-wrapper) {
  min-width: 0;
}

.provider-section-error {
  margin-bottom: 12px;
}

.provider-tag-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
  max-width: min(100%, 560px);
}

.provider-tag {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  white-space: normal;
  overflow-wrap: anywhere;
}

.provider-gap-alert {
  margin-bottom: 0;
}

.provider-kpi-strip {
  align-items: center;
  border-left: 3px solid var(--sg-primary);
}

.provider-kpi-strip .kpi-item {
  min-height: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.provider-context-copy {
  min-width: 220px;
  padding-right: 20px;
  border-right: 1px solid var(--sg-border-soft);
}

.provider-context-copy span,
.provider-context-copy strong,
.provider-context-copy small {
  display: block;
}

.provider-context-copy span,
.provider-context-copy small {
  color: var(--sg-ink-muted);
  font-size: var(--sg-fz-xs);
}

.provider-context-copy strong {
  margin-top: 3px;
  font-family: var(--sg-font-serif);
  font-size: var(--sg-fz-lg);
  font-weight: 600;
}

.provider-context-copy small {
  margin-top: 2px;
}

.provider-kpi-strip .kpi-item strong.is-warning {
  color: var(--sg-warning);
}

.provider-status-grid {
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 0.65fr);
}

.provider-snapshot-grid {
  align-items: start;
}

.location-form-panel {
  align-self: stretch;
}

.location-form {
  display: grid;
  gap: 12px;
}

.location-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(140px, 1fr));
  gap: 12px;
}

.filter-actions {
  display: flex;
  align-items: flex-end;
  min-height: 54px;
}

.section-pagination {
  margin-top: 12px;
}

@media (max-width: 1200px) {
  .provider-status-grid,
  .provider-snapshot-grid {
    grid-template-columns: 1fr;
  }

  .provider-filter-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .page-head {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .provider-tag-list {
    justify-content: flex-start;
  }

  .toolbar-actions .el-button,
  .filter-actions .el-button {
    width: 100%;
  }

  .provider-filter-bar,
  .location-form-grid {
    grid-template-columns: 1fr;
  }

  .provider-context-copy {
    width: 100%;
    padding: 0 0 12px;
    border-right: 0;
    border-bottom: 1px solid var(--sg-border-soft);
  }
}
</style>
