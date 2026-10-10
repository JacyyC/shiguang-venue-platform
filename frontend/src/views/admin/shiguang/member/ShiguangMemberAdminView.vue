<template>
  <div ref="rootEl" class="sga-ops" data-test="sga-member-root">
    <div class="b-heading">
      <div>
        <div class="b-kicker">平台运营 · 会员与权益</div>
        <h1>会员与权益管理</h1>
        <p>从账户的成长与积分，到目录的每一个版本，让会员规则与异常处理都有清楚的依据。</p>
      </div>
    </div>

    <div class="b-tabs" role="group" aria-label="工作对象">
      <button
        v-for="item in TABS"
        :id="`tab-${item.key}`"
        :key="item.key"
        type="button"
        :data-test="`tab-${item.key}`"
        :aria-pressed="view === item.key"
        @click="go(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <section v-if="surface === 'loading'" class="b-state" aria-busy="true" data-test="member-loading">
      <h2>正在读取当前范围</h2>
      <p>账户、规则与目录版本将在加载后完整显示。</p>
      <button class="btn primary" type="button" data-test="member-reload" @click="bootstrap">重新读取</button>
    </section>

    <section v-else-if="surface === 'error'" class="b-state" role="alert" data-test="member-error">
      <h2>暂时无法读取会员数据</h2>
      <p>{{ loadError || '尚未取得本次结果，请重试。' }}</p>
      <button class="btn primary" type="button" data-test="member-reload" @click="bootstrap">重试读取</button>
    </section>

    <template v-else>
      <div v-if="!canViewCurrent" class="b-state" role="status" :data-test="`denied-${view}`">
        <h2>{{ DENIED[view].title }}</h2>
        <p>{{ DENIED[view].text }}</p>
        <button v-if="view !== 'events' && canView('events')" class="btn" type="button" data-test="goto-events" @click="go('events')">
          查看权益事件
        </button>
      </div>

      <!-- 会员账户登记册 -->
      <template v-else-if="view === 'accounts'">
        <div class="b-section-title">
          <div>
            <h2>会员账户登记册</h2>
            <p>找到会员后核对账户，不在此调整资金或积分。</p>
          </div>
        </div>
        <form class="b-search" data-test="account-search-form" @submit.prevent="searchAccounts">
          <input
            id="account-search"
            v-model="accountKeyword"
            data-test="account-search"
            aria-label="搜索会员用户名或昵称"
            placeholder="输入会员用户名或昵称"
          >
          <button class="btn primary" type="submit" data-test="account-search-submit">查找</button>
        </form>
        <div v-if="accountConflict" class="b-readonly" role="status" data-test="account-conflict">
          {{ accountConflict }}
        </div>
        <div v-if="overview" class="b-overview-note" data-test="account-overview-note">
          <span v-for="row in countRows" :key="row.label"><strong>{{ groupedInt(row.value) }}</strong>{{ row.label }}</span>
        </div>
        <!-- 参考的账户表是裸 table（靠 .b-hide-small/.b-hide-medium 在窄屏减列），
             不套 .b-table-wrap；该包裹类在参考里只用于「权益事件」表。 -->
        <table v-if="accounts.length" class="b-ledger" data-test="account-table">
          <thead>
            <tr>
              <th>会员</th>
              <th>等级</th>
              <th>成长值</th>
              <th class="b-hide-small">可用积分</th>
              <th class="b-hide-medium">累计消费</th>
              <th class="b-hide-medium">最近活跃</th>
              <th>详情</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in accounts" :key="row.userId">
              <td>
                <strong>{{ accountDisplayName(row) }}</strong>
                <span class="b-sub">{{ accountSubtitle(row) }}</span>
              </td>
              <td>{{ row.levelName || '—' }}</td>
              <td class="b-num">{{ groupedInt(row.growthValue) }}</td>
              <td class="b-hide-small b-num">{{ groupedInt(row.pointsBalance) }} 积分</td>
              <td class="b-hide-medium b-num">¥ {{ money(row.totalConsumptionAmount) }}</td>
              <td class="b-hide-medium">{{ shortDateTime(row.lastActiveTime) || '暂无' }}</td>
              <td>
                <button
                  class="b-link"
                  type="button"
                  :data-test="`account-open-${row.userId}`"
                  :aria-label="`查看 ${accountDisplayName(row)} 的账户`"
                  @click="openAccount(row, $event)"
                >
                  查看
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="b-state" data-test="account-empty">
          <h2>没有找到相符会员</h2>
          <p>尝试用户名或昵称的一部分，或清除条件。</p>
          <button class="btn" type="button" data-test="account-clear" @click="clearAccountSearch">清除搜索</button>
        </div>
        <p class="b-sub" data-test="account-paging">
          共 {{ accountTotal }} 条，按 {{ accountPage.pageSize }} 条分页；当前第 {{ accountPage.pageNum }} 页。
        </p>
        <div class="b-actions">
          <button class="btn" type="button" data-test="account-prev" :disabled="accountPage.pageNum <= 1" @click="moveAccountPage(-1)">上一页</button>
          <button class="btn" type="button" data-test="account-next" :disabled="accountPage.pageNum * accountPage.pageSize >= accountTotal" @click="moveAccountPage(1)">下一页</button>
        </div>

        <!-- 逐字对齐参考 ref/b/members.js:6 的内联样式：这里是账户页自己的折叠块，
             不是预览审阅面板（.b-review 带 13px/muted，会把内部 dl 的字体与颜色一起改掉）。 -->
        <details
          data-test="account-samples"
          style="margin-top: 26px; border-top: 1px solid var(--line); padding-top: 12px"
        >
          <summary style="min-height: 44px; cursor: pointer">会员概览与展示样本</summary>
          <div v-if="overview" class="b-overview-note">
            <span v-for="row in levelRows" :key="row.label">{{ row.label }} <strong>{{ groupedInt(row.value) }}</strong></span>
          </div>
          <!-- 真实口径：activeMembers 统计 member_account.status='ACTIVE'，而没有任何路径把账户置为非启用，
               因此它与总数相等（B4-M8）；样本为 LIMIT 10 的展示行，不是全量人数。 -->
          <p class="muted">启用会员账户与服务端统计口径一致；高价值与沉睡列表是服务端返回的展示样本（最多 10 条），不代表完整人数。</p>
          <dl class="b-definition">
            <dt>高价值展示样本</dt>
            <dd v-if="coreSample">{{ coreSample }}</dd>
            <dd v-else class="muted">当前没有返回高价值样本。</dd>
            <dt>沉睡展示样本</dt>
            <dd v-if="dormantSample">{{ dormantSample }}</dd>
            <dd v-else class="muted">当前没有返回沉睡样本。</dd>
          </dl>
        </details>
        <p class="b-page-bottom">账户中的积分用于兑换，成长值用于等级；累计消费是统计，不是储值余额，本面不提供资金或积分的调整入口。</p>
      </template>

      <!-- 等级规则 -->
      <template v-else-if="view === 'levels'">
        <div class="b-section-title">
          <div>
            <h2>成长对应的会员规则</h2>
            <p>门槛、会员折扣和说明一起核对，再保存规则。</p>
          </div>
          <button class="btn primary" type="button" data-test="level-create" @click="openLevelForm(-1, $event)">新增等级</button>
        </div>
        <p v-if="!levels.length" class="b-readonly">当前没有可用的会员等级规则。</p>
        <div v-else class="b-level-grid" data-test="level-grid">
          <article v-for="(level, index) in levels" :key="level.id" class="b-level">
            <div class="b-inline">
              <span class="b-kicker">{{ level.levelCode }}</span>
              <span class="b-status" :class="{ attention: statusAttention(levelStatusLabel(level.status)) }">
                {{ levelStatusLabel(level.status) }}
              </span>
            </div>
            <h3>{{ level.levelName }}</h3>
            <span class="b-threshold">{{ growthThresholdText(level) }}</span>
            <span class="b-sub">成长值门槛</span>
            <p>{{ level.benefitText || '尚未填写会员权益说明。' }}</p>
            <div class="b-overview-note">
              <span>会员折扣 <strong>{{ discountRateLabel(level.discountRate) }}</strong></span>
              <span>规则 {{ level.ruleVersion || '—' }}</span>
            </div>
            <div class="b-actions">
              <button class="btn" type="button" :data-test="`level-edit-${index}`" @click="openLevelForm(index, $event)">编辑规则</button>
              <button class="b-link" type="button" :data-test="`level-toggle-${index}`" @click="openToggle('levels', index, $event)">
                {{ levelStatusLabel(level.status) === '启用' ? '停用' : '启用' }}
              </button>
            </div>
          </article>
        </div>
        <!-- 真实合同：等级状态没有状态机、CAS 或事件（B4-M7），服务端只做枚举归一与 400；
             因此本面不做 409 前置，也不要声称「已保留交易记录」之类的联动语义。 -->
        <p class="b-page-bottom">
          等级状态只表达该规则是否参与成长值匹配；服务端不校验状态迁移，也不会写入独立的状态变更事件。停用不影响已按原规则结算的存量记录。
        </p>
      </template>

      <!-- 权益目录与当前版本 -->
      <template v-else-if="view === 'rewards'">
        <div class="b-section-title">
          <div>
            <h2>权益目录与当前版本</h2>
            <p>发布版本时核对积分成本、会员等级和关联券模板。</p>
          </div>
          <button class="btn primary" type="button" data-test="reward-create" @click="openCreateCatalog($event)">新增目录</button>
        </div>
        <!-- B4-M4：等级与券模板是域外依赖，分别需要 member:manage 与 coupon:manage；无权时给出局部提示而不是空下拉。 -->
        <div v-if="levelsError || templatesError" class="b-readonly" role="status" data-test="reward-dependency-note">
          <p v-if="levelsError">会员等级选项不可用：{{ levelsError }}</p>
          <p v-if="templatesError">优惠券模板选项不可用：{{ templatesError }}</p>
          <p>发布版本需要同时具备会员等级与优惠券模板的读取权限，缺少任一项都无法完整核对兑换条件。</p>
        </div>
        <p v-if="!catalogItems.length" class="b-readonly" data-test="reward-empty">当前没有已登记的权益目录。</p>
        <article v-for="(item, index) in catalogItems" v-else :key="item.itemId" class="b-list-item">
          <div>
            <div class="b-inline">
              <span class="b-kicker">{{ item.itemCode }} · {{ catalogVersionCaption(hasPublishedVersion(item.versionId), item.versionNo) }}</span>
              <span class="b-status" :class="{ attention: statusAttention(catalogStatusLabel(item.status)) }">
                {{ catalogStatusLabel(item.status) }}
              </span>
            </div>
            <h3>{{ item.itemName }}</h3>
            <p>{{ catalogConditionText(item) }}</p>
            <span class="b-sub">关联模板：{{ templateName(item.couponTemplateId) }}</span>
            <span v-if="item.descriptionText" class="b-sub">{{ item.descriptionText }}</span>
          </div>
          <div class="b-actions">
            <button class="btn" type="button" :data-test="`reward-publish-${index}`" @click="openPublish(index, $event)">发布新版本</button>
            <button class="b-link" type="button" :data-test="`reward-toggle-${index}`" @click="openToggle('rewards', index, $event)">
              {{ catalogStatusLabel(item.status) === '已启用' ? '停用' : '启用' }}
            </button>
          </div>
        </article>
        <p class="b-page-bottom">
          每次兑换保留对应版本；发布新版本前，在发布面比较当前值与本次变更。已有兑换仍保留原版本，发布不改写历史。
        </p>
      </template>

      <!-- 异常兑换 -->
      <template v-else-if="view === 'exceptions'">
        <div class="b-section-title">
          <div>
            <h2>需要继续处理的兑换</h2>
            <p>失败、处理中与拒绝分别处理，最近命令结果暂留供核对。</p>
          </div>
          <span class="b-status">{{ exceptionTotal }} 条</span>
        </div>
        <div class="b-field" style="max-width: 320px">
          <label>兑换状态</label>
          <select v-model="exceptionStatus" data-test="exception-status" @change="reloadExceptions">
                      <option v-for="option in EXCEPTION_STATUS_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                    </select>
        </div>
        <p v-if="!exceptions.length" class="b-readonly" data-test="exception-empty">当前范围内没有需要处理的兑换。</p>
        <article v-for="(row, index) in exceptions" v-else :key="row.id" class="b-list-item">
          <div>
            <div class="b-inline">
              <span class="b-kicker">{{ row.redemptionNo }}</span>
              <span class="b-status" :class="{ attention: statusAttention(redemptionStatusLabel(row.status)) }">
                {{ redemptionStatusLabel(row.status) }}
              </span>
            </div>
            <h3>{{ row.itemName }}</h3>
            <p>兑换积分成本 {{ groupedInt(row.pointsCost) }} 积分 · 目录版本 V{{ row.versionNo ?? '—' }}</p>
            <p class="b-reason">{{ row.failureMessage || '服务端未提供失败说明。' }}</p>
          </div>
          <button class="btn" type="button" :data-test="`exception-open-${index}`" @click="openException(index, $event)">核对与处理</button>
        </article>
        <p class="b-sub" data-test="exception-paging">
          共 {{ exceptionTotal }} 条，按 {{ exceptionPage.pageSize }} 条分页；当前第 {{ exceptionPage.pageNum }} 页。
        </p>
        <div class="b-actions">
          <button class="btn" type="button" data-test="exception-prev" :disabled="exceptionPage.pageNum <= 1" @click="moveExceptionPage(-1)">上一页</button>
          <button class="btn" type="button" data-test="exception-next" :disabled="exceptionPage.pageNum * exceptionPage.pageSize >= exceptionTotal" @click="moveExceptionPage(1)">下一页</button>
        </div>
      </template>

      <!-- 权益事件 -->
      <template v-else>
        <div class="b-section-title">
          <div>
            <h2>权益事件记录</h2>
            <p>只读查看对象、状态变化和来源，便于追溯处理依据。</p>
          </div>
          <span class="b-status">只读记录</span>
        </div>
        <p v-if="!events.length" class="b-readonly" data-test="event-empty">当前没有权益事件记录。</p>
        <div v-else class="b-table-wrap" tabindex="0" aria-label="权益事件记录表，可横向滚动">
          <table class="b-ledger" data-test="event-table">
            <thead>
              <tr><th>时间</th><th>事件 / 对象</th><th>状态变化</th><th>来源</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in events" :key="row.id">
                <td>{{ shortDateTime(row.occurredAt) }}</td>
                <td>
                  <strong>{{ memberRewardEventTypeLabel(row.eventType) }}</strong>
                  <span class="b-sub">{{ eventSubject(row) }}</span>
                </td>
                <td>{{ statusTransitionLabel(row.fromStatus, row.toStatus) || '—' }}</td>
                <td>{{ memberRewardSourceLabel(row.sourceType) || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="b-sub" data-test="event-paging">
          共 {{ eventTotal }} 条，按 {{ eventPage.pageSize }} 条分页；当前第 {{ eventPage.pageNum }} 页。
        </p>
        <div class="b-actions">
          <button class="btn" type="button" data-test="event-prev" :disabled="eventPage.pageNum <= 1" @click="moveEventPage(-1)">上一页</button>
          <button class="btn" type="button" data-test="event-next" :disabled="eventPage.pageNum * eventPage.pageSize >= eventTotal" @click="moveEventPage(1)">下一页</button>
        </div>
        <!-- 事件类型与来源在服务端既无 DB CHECK 也无 Java 枚举（B4-M11），这里按代码字面量建白名单并原值回落。 -->
        <p class="b-page-bottom">事件类型与来源由服务端写入，未登记的类型原样显示，不在前端改写或隐藏。</p>
      </template>
    </template>

    <dialog ref="dialogEl" class="b-dialog" aria-labelledby="b-dialog-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <form v-if="dialog" data-test="member-dialog-form" @submit.prevent="confirmDialog">
        <div class="b-dialog-head">
          <h2 id="b-dialog-title" data-test="member-dialog-title" tabindex="-1">{{ dialog.title }}</h2>
          <button class="b-close" type="button" aria-label="关闭详情" data-test="member-dialog-close" @click="closeDialog">×</button>
        </div>
        <div class="b-dialog-body">
          <!-- 账户详情 -->
          <template v-if="dialog.kind === 'account' && accountDetail">
            <p class="muted">{{ accountSubtitle(accountDetail) }} · {{ accountDetail.levelName || '未定级' }}会员</p>
            <dl class="b-metadata">
              <div><dt>成长值</dt><dd>{{ groupedInt(accountDetail.growthValue) }} 成长值</dd></div>
              <div><dt>可用积分</dt><dd>{{ groupedInt(accountDetail.pointsBalance) }} 积分</dd></div>
              <div><dt>会员价格</dt><dd>{{ discountRateLabel(accountDetail.discountRate) }}</dd></div>
            </dl>
            <hr class="b-divider">
            <dl class="b-definition">
              <dt>累计消费</dt><dd>¥ {{ money(accountDetail.totalConsumptionAmount) }}</dd>
              <dt>会员优惠累计</dt><dd>¥ {{ money(accountDetail.totalMemberDiscountAmount) }}</dd>
              <dt>预约次数</dt><dd>{{ accountDetail.totalReservations ?? 0 }} 次</dd>
              <dt>最近活跃</dt><dd>{{ shortDateTime(accountDetail.lastActiveTime) || '暂无' }}</dd>
              <dt>下一等级</dt>
              <dd v-if="accountDetail.nextLevel">
                {{ accountDetail.nextLevel.levelName }} · 还需 {{ groupedInt(accountDetail.nextLevelGrowthGap) }} 成长值
              </dd>
              <dd v-else>已是当前最高等级。</dd>
              <dt>账户说明</dt>
              <dd>成长与积分分别记录；消费与优惠是累计统计，不提供资金余额或人工调账。</dd>
            </dl>
          </template>

          <!-- 等级新增/编辑 -->
          <template v-else-if="dialog.kind === 'level'">
            <div class="b-form-grid">
              <div class="b-field">
                <label>等级名称</label>
                <input v-model="levelForm.levelName" data-test="level-name" required maxlength="30" autocomplete="off">
              </div>
              <div class="b-field">
                <label>等级编码</label>
                <input v-model="levelForm.levelCode" data-test="level-code" required autocomplete="off">
              </div>
              <div class="b-field">
                <label>成长值门槛</label>
                <input v-model.number="levelForm.growthThreshold" data-test="level-growth" type="number" required min="0">
              </div>
              <div class="b-field">
                <label>折扣率（1 为标准价）</label>
                <input v-model.number="levelForm.discountRate" data-test="level-rate" type="number" required min="0.01" max="1" step="0.01">
              </div>
              <div class="b-field">
                <label>规则版本</label>
                <input v-model="levelForm.ruleVersion" data-test="level-version" required autocomplete="off">
              </div>
              <label class="b-field b-span">会员权益说明
                <textarea v-model="levelForm.benefitText" data-test="level-benefit" required></textarea>
              </label>
            </div>
            <p class="b-sub">规则版本随表单提交；留空会被服务端改写为默认版本并覆盖库中已有值。</p>
          </template>

          <!-- 状态启停确认 -->
          <template v-else-if="dialog.kind === 'toggle'">
            <p>{{ dialog.bodyText }}</p>
            <p class="b-reason">{{ dialog.detail }}</p>
          </template>

          <!-- 新增目录 -->
          <template v-else-if="dialog.kind === 'catalog'">
            <div class="b-form-grid">
              <div class="b-field">
                <label>目录名称</label>
                <input v-model="catalogForm.itemName" data-test="catalog-name" required maxlength="128" autocomplete="off">
              </div>
              <div class="b-field">
                <label>目录编码</label>
                <input v-model="catalogForm.itemCode" data-test="catalog-code" required maxlength="64" autocomplete="off">
              </div>
              <label class="b-field b-span">目录说明（可选）
                <input v-model="catalogForm.descriptionText" data-test="catalog-description" maxlength="500" autocomplete="off">
              </label>
              <p class="b-span b-reason">先登记目录，再发布包含积分成本与券模板的首个版本；登记后目录为草稿状态。</p>
            </div>
          </template>

          <!-- 发布新版本 -->
          <template v-else-if="dialog.kind === 'publish'">
            <div class="b-edit-layout">
              <div class="b-form-grid">
                <div class="b-field">
                  <label>积分成本</label>
                  <input v-model.number="versionForm.pointsCost" data-test="version-points" type="number" required min="1" max="1000000000">
                </div>
                <div class="b-field">
                  <label>最低会员等级</label>
                  <select v-model="versionForm.minimumLevelCode" data-test="version-level">
                                      <option v-for="level in activeLevels" :key="level.levelCode" :value="level.levelCode">{{ level.levelName }}</option>
                                    </select>
                </div>
                <div class="b-field">
                  <label>关联优惠券模板</label>
                  <select v-model.number="versionForm.couponTemplateId" data-test="version-template">
                                      <option :value="0" disabled>请选择可发放的券模板</option>
                                      <option v-for="template in activeTemplates" :key="template.id" :value="template.id">
                                        {{ template.templateName }}
                                      </option>
                                    </select>
                </div>
                <div class="b-field">
                  <label>券有效天数</label>
                  <input v-model.number="versionForm.validityDays" data-test="version-days" type="number" required min="1" max="3650">
                </div>
                <div class="b-field">
                  <label>生效开始时间</label>
                  <input v-model="versionForm.effectiveStartTime" data-test="version-start" type="datetime-local">
                </div>
                <div class="b-field">
                  <label>生效结束时间（可选）</label>
                  <input v-model="versionForm.effectiveEndTime" data-test="version-end" type="datetime-local">
                </div>
              </div>
              <aside class="b-summary">
                <h3>{{ dialog.caption }}</h3>
                <p v-if="publishCurrent">{{ publishCurrent }}</p>
                <!-- §9.2 B4-M5：无生效版本时显式降级，不用前端版本号伪造历史。 -->
                <p v-else class="b-reason">当前没有已发布的兑换版本，本次发布将是该目录的首个版本。</p>
                <hr class="b-divider">
                <h3>{{ dialog.nextCaption }}</h3>
                <p>使用左侧填写的条件。已有兑换仍保留原版本，发布不改写历史。</p>
              </aside>
            </div>
            <p class="b-sub">生效开始时间留空表示立即生效；结束时间必须晚于开始时间。</p>
          </template>

          <!-- 异常核对与处理 -->
          <template v-else-if="dialog.kind === 'exception' && activeException">
            <h3>{{ activeException.itemName }}</h3>
            <dl class="b-definition">
              <dt>兑换编号</dt><dd>{{ activeException.redemptionNo }}</dd>
              <dt>兑换状态</dt>
              <dd>
                <span class="b-status" :class="{ attention: statusAttention(redemptionStatusLabel(activeException.status)) }">
                  {{ redemptionStatusLabel(activeException.status) }}
                </span>
              </dd>
              <dt>兑换积分成本</dt><dd>{{ groupedInt(activeException.pointsCost) }} 积分</dd>
              <dt>失败代码</dt><dd>{{ activeException.failureCode || '—' }}</dd>
              <dt>当前说明</dt><dd>{{ activeException.failureMessage || '服务端未提供失败说明。' }}</dd>
            </dl>
            <hr class="b-divider">
            <p class="b-reason">
              失败兑换可对原请求重试。只有兑换成功后才可申请补偿；补偿会作废可补偿的券并退回积分，已锁定、已使用或已退款的券将被拒绝。
            </p>
            <label v-if="canCompensate(activeException.status, activeException.userCouponId)" class="b-field">
              补偿原因（申请时必填）
              <textarea v-model="compensateReason" data-test="compensate-reason" maxlength="255" placeholder="说明需要补偿的原因"></textarea>
            </label>
          </template>
        </div>
        <div class="b-dialog-foot">
          <button class="btn" type="button" data-test="member-dialog-back" @click="closeDialog">{{ dialog.cancelText }}</button>
          <button
            v-if="dialog.submit"
            class="btn primary"
            type="button"
            data-test="member-dialog-submit"
            :disabled="dialog.busy || dialogDisabled"
            @click="confirmDialog"
          >
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
        <p v-if="dialog.error" class="b-error" role="alert" data-test="member-dialog-error">{{ dialog.error }}</p>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminMemberApi, adminMemberRewardApi } from '@/api/member'
import { promotionAdminApi } from '@/api/promotion'
import { useAuthStore } from '@/stores/auth'
import { useAppStore } from '@/stores/app'
import { ISOLATED_REQUEST as isolated, consumeTabFocus, focusable, money, rememberTabFocus, resolveInitialTab, restoreFocusChain } from '../adminShared'
import type {
  CouponTemplateResponse,
  MemberAccountResponse,
  MemberAccountSummary,
  MemberLevelResponse,
  MemberOverviewResponse,
  MemberRewardCatalogResponse,
  MemberRewardEventResponse,
  MemberRewardRedemptionResponse
} from '@/api/types'
import {
  EXCEPTION_STATUS_OPTIONS,
  accountDisplayName,
  accountSubtitle,
  canCompensate,
  canRetry,
  catalogConditionText,
  catalogStatusLabel,
  catalogToggleTarget,
  catalogVersionCaption,
  discountRateLabel,
  groupedInt,
  hasPublishedVersion,
  levelDistributionRows,
  levelStatusLabel,
  LEVEL_STATUS_ACTIVE,
  LEVEL_STATUS_DISABLED,
  memberRewardEventTypeLabel,
  memberRewardSourceLabel,
  overviewCountRows,
  redemptionStatusLabel,
  shortDateTime,
  statusTransitionLabel,
  growthThresholdText
} from './memberAdminShared'

type MemberView = 'accounts' | 'levels' | 'rewards' | 'exceptions' | 'events'
type SurfaceState = 'loading' | 'ready' | 'error'
type DialogKind = 'account' | 'level' | 'toggle' | 'catalog' | 'publish' | 'exception'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  cancelText: string
  bodyText: string
  detail: string
  caption: string
  nextCaption: string
  error: string
  busy: boolean
  index: number
}

const TABS: Array<{ key: MemberView; label: string }> = [
  { key: 'accounts', label: '会员账户' },
  { key: 'levels', label: '等级规则' },
  { key: 'rewards', label: '权益目录' },
  { key: 'exceptions', label: '异常兑换' },
  { key: 'events', label: '权益事件' }
]

/** 每个页签的真实叶子权限点。账户与等级同属 `member:manage`（服务端读写同点）。 */
const TAB_PERMISSIONS: Record<MemberView, string> = {
  accounts: 'member:manage',
  levels: 'member:manage',
  rewards: 'member-reward:catalog:manage',
  exceptions: 'member-reward:exception:manage',
  events: 'member-reward:audit:view'
}

const DENIED: Record<MemberView, { title: string; text: string }> = {
  accounts: { title: '没有会员账户查看权限', text: '账户读取与等级规则需要 member:manage。' },
  levels: { title: '没有会员等级规则权限', text: '等级新增、编辑与启停需要 member:manage。' },
  rewards: { title: '没有权益目录管理权限', text: '目录登记、版本发布与状态维护需要 member-reward:catalog:manage。' },
  exceptions: { title: '没有异常兑换处置权限', text: '重试与补偿需要 member-reward:exception:manage。' },
  events: { title: '没有权益事件查看权限', text: '只读事件记录需要 member-reward:audit:view。' }
}

/** 路由名 → 初始页签：两条既有路由各自落到对应的页签，path/name/menuKey 均不变。 */
const ROUTE_VIEWS: Record<string, MemberView> = {
  SystemMembers: 'accounts',
  SystemRewardCatalog: 'rewards'
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)

const surface = ref<SurfaceState>('loading')
const loadError = ref('')
const view = ref<MemberView>(initialView())
const dialog = ref<DialogState | null>(null)
const lastTrigger = ref<HTMLElement | null>(null)
const lastTriggerTest = ref<string | null>(null)
const restoring = ref(false)

const accounts = ref<MemberAccountSummary[]>([])
const accountTotal = ref(0)
const accountPage = reactive({ pageNum: 1, pageSize: 10 })
const accountKeyword = ref('')
const accountConflict = ref('')
const overview = ref<MemberOverviewResponse | null>(null)
const accountDetail = ref<MemberAccountResponse | null>(null)

const levels = ref<MemberLevelResponse[]>([])
const levelForm = reactive({
  id: null as number | null,
  levelName: '',
  levelCode: '',
  growthThreshold: 0,
  discountRate: 1,
  ruleVersion: '',
  benefitText: ''
})

const catalogItems = ref<MemberRewardCatalogResponse[]>([])
const templates = ref<CouponTemplateResponse[]>([])
const levelsError = ref('')
const templatesError = ref('')
const catalogForm = reactive({ itemName: '', itemCode: '', descriptionText: '' })
const versionForm = reactive({
  itemId: 0,
  pointsCost: 1,
  minimumLevelCode: '',
  couponTemplateId: 0,
  validityDays: 1,
  effectiveStartTime: '',
  effectiveEndTime: ''
})

const exceptions = ref<MemberRewardRedemptionResponse[]>([])
const exceptionTotal = ref(0)
const exceptionPage = reactive({ pageNum: 1, pageSize: 10 })
const exceptionStatus = ref('')
const activeException = ref<MemberRewardRedemptionResponse | null>(null)
const compensateReason = ref('')

const events = ref<MemberRewardEventResponse[]>([])
const eventTotal = ref(0)
const eventPage = reactive({ pageNum: 1, pageSize: 10 })

const activeLevels = computed(() => levels.value.filter((level) => levelStatusLabel(level.status) === '启用'))
const activeTemplates = computed(() => templates.value.filter((template) => (template.status ?? '').toUpperCase() === 'ACTIVE'))
const canViewCurrent = computed(() => canView(view.value))
const countRows = computed(() => overviewCountRows(overview.value))
const levelRows = computed(() => levelDistributionRows(overview.value))
const coreSample = computed(() => sampleLine(overview.value?.coreUsers?.[0]))
const dormantSample = computed(() => sampleLine(overview.value?.dormantUsers?.[0]))
const publishCurrent = computed(() => {
  if (dialog.value?.kind !== 'publish') return ''
  const item = catalogItems.value.find((row) => row.itemId === versionForm.itemId)
  if (!item || !hasPublishedVersion(item.versionId)) return ''
  const template = templates.value.find((row) => row.id === item.couponTemplateId)
  return [
    `${groupedInt(item.pointsCost)} 积分`,
    `${item.minimumLevelName || item.minimumLevelCode || '不限等级'}及以上`,
    template?.templateName || `券模板 #${item.couponTemplateId ?? '—'}`,
    `券有效 ${item.validityDays ?? '—'} 天`
  ].join('\n')
})
const dialogDisabled = computed(() => {
  if (dialog.value?.kind !== 'publish') return false
  return !versionForm.couponTemplateId || !versionForm.minimumLevelCode
})

function initialView(): MemberView {
  return resolveInitialTab<MemberView>({
    tabKeys: TABS.map((tab) => tab.key),
    routeTabs: ROUTE_VIEWS,
    routeName: route.name,
    hash: typeof route.hash === 'string' ? route.hash : '',
    canView
  })
}

function canView(target: MemberView): boolean {
  return auth.hasPermission(TAB_PERMISSIONS[target])
}

function statusAttention(label: string): boolean {
  return /失败|待|异常|锁定/.test(label)
}

function sampleLine(account: MemberAccountSummary | undefined): string {
  if (!account) return ''
  return `${accountDisplayName(account)} · ${account.levelName || '未定级'} · 成长值 ${groupedInt(account.growthValue)}`
}

function eventSubject(row: MemberRewardEventResponse): string {
  if (row.redemptionId != null) return `兑换 ${row.redemptionId}`
  if (row.catalogItemId != null) return `目录 ${row.catalogItemId}`
  return '—'
}

function templateName(templateId: number | null | undefined): string {
  if (templateId == null) return '尚未关联'
  const found = templates.value.find((row) => row.id === templateId)
  return found?.templateName || `券模板 #${templateId}`
}

function readableError(error: unknown, fallback: string): string {
  const response = (error as { response?: { data?: { message?: string } } })?.response
  return response?.data?.message || (error as Error)?.message || fallback
}

/* --------------------------------------------------------------- 数据加载 */

async function loadAccounts(): Promise<void> {
  const [page, summary] = await Promise.all([
    adminMemberApi.accounts({ pageNum: accountPage.pageNum, pageSize: accountPage.pageSize, ...(accountKeyword.value.trim() ? { keyword: accountKeyword.value.trim() } : {}) }, isolated),
    adminMemberApi.overview(isolated)
  ])
  accounts.value = page.records || []
  accountTotal.value = page.total ?? 0
  overview.value = summary
}

async function loadLevels(): Promise<void> {
  levels.value = await adminMemberApi.levels(isolated)
}

/**
 * 目录页签同时依赖域外两读：会员等级（`member:manage`）与可发放券模板（`coupon:manage`）。
 * 两者失败时**分别**记录原因并给出局部提示，而不是静默回落空数组（B4-M4 / B4-P9）。
 * 券模板按 `status=ACTIVE` 过滤：服务端此前忽略该入参，S03 已补真实过滤。
 */
async function loadCatalog(): Promise<void> {
  levelsError.value = ''
  templatesError.value = ''
  const [items, levelList, templatePage] = await Promise.all([
    adminMemberRewardApi.listCatalogItems(isolated),
    adminMemberApi.levels(isolated).catch((error) => {
      levelsError.value = readableError(error, '缺少会员等级读取权限（member:manage）。')
      return [] as MemberLevelResponse[]
    }),
    promotionAdminApi
      .couponTemplates({ pageNum: 1, pageSize: 100, status: 'ACTIVE' }, isolated)
      .then((page) => page.records || [])
      .catch((error) => {
        templatesError.value = readableError(error, '缺少优惠券模板读取权限（coupon:manage）。')
        return [] as CouponTemplateResponse[]
      })
  ])
  catalogItems.value = items
  levels.value = levelList
  templates.value = templatePage
}

async function loadExceptions(): Promise<void> {
  const page = await adminMemberRewardApi.exceptions(
    { pageNum: exceptionPage.pageNum, pageSize: exceptionPage.pageSize, ...(exceptionStatus.value ? { status: exceptionStatus.value } : {}) },
    isolated
  )
  exceptions.value = page.records || []
  exceptionTotal.value = page.total ?? 0
}

async function loadEvents(): Promise<void> {
  const page = await adminMemberRewardApi.events({ pageNum: eventPage.pageNum, pageSize: eventPage.pageSize }, isolated)
  events.value = page.records || []
  eventTotal.value = page.total ?? 0
}

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  loadError.value = ''
  try {
    if (!canViewCurrent.value) {
      surface.value = 'ready'
      return
    }
    if (view.value === 'accounts') await loadAccounts()
    else if (view.value === 'levels') await loadLevels()
    else if (view.value === 'rewards') await loadCatalog()
    else if (view.value === 'exceptions') await loadExceptions()
    else await loadEvents()
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    loadError.value = readableError(error, '会员与权益数据暂时无法读取。')
  }
}

/* ----------------------------------------------------------------- 页签 */

function applyView(next: MemberView): void {
  view.value = next
  void router.replace({ hash: `#${next}` })
}

function go(next: MemberView): void {
  if (view.value === next) return
  rememberTabFocus(next)
  applyView(next)
  void bootstrap()
  void nextTick(() => document.getElementById(`tab-${next}`)?.focus())
}

/* --------------------------------------------------------------- 账户面 */

function searchAccounts(): void {
  accountPage.pageNum = 1
  void bootstrap()
}

function clearAccountSearch(): void {
  accountKeyword.value = ''
  accountPage.pageNum = 1
  void bootstrap()
}

function moveAccountPage(delta: number): void {
  accountPage.pageNum = Math.max(1, accountPage.pageNum + delta)
  void bootstrap()
}

async function openAccount(row: MemberAccountSummary, event?: Event): Promise<void> {
  rememberTrigger(event, `account-open-${row.userId}`)
  accountDetail.value = null
  openDialog({
    kind: 'account',
    title: accountDisplayName(row),
    submit: '',
    cancelText: '返回账户登记册',
    bodyText: '',
    detail: '',
    caption: '',
    nextCaption: '',
    index: -1
  })
  try {
    accountDetail.value = await adminMemberApi.account(row.userId, isolated)
  } catch (error) {
    setDialogError(readableError(error, '账户详情暂时无法读取。'))
  }
}

/* --------------------------------------------------------------- 等级面 */

function openLevelForm(index: number, event?: Event): void {
  const level = index >= 0 ? levels.value[index] : null
  rememberTrigger(event, index >= 0 ? `level-edit-${index}` : 'level-create')
  levelForm.id = level?.id ?? null
  levelForm.levelName = level?.levelName ?? ''
  levelForm.levelCode = level?.levelCode ?? ''
  levelForm.growthThreshold = level?.growthThreshold ?? 0
  levelForm.discountRate = level?.discountRate ?? 1
  // B4-M6：规则版本必须回传，否则服务端会用默认值覆盖库中已有版本。
  levelForm.ruleVersion = level?.ruleVersion ?? ''
  levelForm.benefitText = level?.benefitText ?? ''
  openDialog({
    kind: 'level',
    title: index < 0 ? '新增会员等级' : `编辑 ${level?.levelName ?? ''} 等级`,
    submit: '保存规则',
    cancelText: '取消',
    bodyText: '',
    detail: '',
    caption: '',
    nextCaption: '',
    index
  })
}

/* ------------------------------------------------------------- 目录与版本 */

function openCreateCatalog(event?: Event): void {
  rememberTrigger(event, 'reward-create')
  catalogForm.itemName = ''
  catalogForm.itemCode = ''
  catalogForm.descriptionText = ''
  openDialog({
    kind: 'catalog',
    title: '新增权益目录',
    submit: '登记目录',
    cancelText: '取消',
    bodyText: '',
    detail: '',
    caption: '',
    nextCaption: '',
    index: -1
  })
}

function openPublish(index: number, event?: Event): void {
  const item = catalogItems.value[index]
  rememberTrigger(event, `reward-publish-${index}`)
  versionForm.itemId = item.itemId
  versionForm.pointsCost = item.pointsCost ?? 1
  versionForm.minimumLevelCode = item.minimumLevelCode || activeLevels.value[0]?.levelCode || ''
  versionForm.couponTemplateId = item.couponTemplateId ?? 0
  versionForm.validityDays = item.validityDays ?? 1
  versionForm.effectiveStartTime = ''
  versionForm.effectiveEndTime = ''
  openDialog({
    kind: 'publish',
    title: `发布「${item.itemName}」新版本`,
    submit: hasPublishedVersion(item.versionId) ? `确认发布 V${(item.versionNo ?? 0) + 1}` : '确认发布首个版本',
    cancelText: '取消',
    bodyText: '',
    detail: '',
    caption: catalogVersionCaption(hasPublishedVersion(item.versionId), item.versionNo),
    nextCaption: hasPublishedVersion(item.versionId) ? `本次发布 V${(item.versionNo ?? 0) + 1}` : '本次发布（首个版本）',
    index
  })
}

/* --------------------------------------------------------------- 异常面 */

function openException(index: number, event?: Event): void {
  const row = exceptions.value[index]
  rememberTrigger(event, `exception-open-${index}`)
  activeException.value = row
  compensateReason.value = ''
  const submit = canCompensate(row.status, row.userCouponId) ? '申请补偿并退回积分' : canRetry(row.status) ? '重试原兑换' : ''
  openDialog({
    kind: 'exception',
    title: `核对兑换 ${row.redemptionNo}`,
    submit,
    cancelText: '返回',
    bodyText: '',
    detail: '',
    caption: '',
    nextCaption: '',
    index
  })
}

function reloadExceptions(): void {
  exceptionPage.pageNum = 1
  void bootstrap()
}

function moveExceptionPage(delta: number): void {
  exceptionPage.pageNum = Math.max(1, exceptionPage.pageNum + delta)
  void bootstrap()
}

function moveEventPage(delta: number): void {
  eventPage.pageNum = Math.max(1, eventPage.pageNum + delta)
  void bootstrap()
}

/* ----------------------------------------------------------------- 弹层 */

function openToggle(kind: 'levels' | 'rewards', index: number, event?: Event): void {
  const target = kind === 'levels' ? levels.value[index] : catalogItems.value[index]
  const current = kind === 'levels'
    ? levelStatusLabel((target as MemberLevelResponse).status)
    : catalogStatusLabel((target as MemberRewardCatalogResponse).status)
  const next = current === '启用' || current === '已启用' ? '停用' : '启用'
  const name = (target as MemberLevelResponse).levelName || (target as MemberRewardCatalogResponse).itemName
  const noun = kind === 'levels' ? '会员等级' : '权益目录'
  rememberTrigger(event, `${kind === 'levels' ? 'level' : 'reward'}-toggle-${index}`)
  openDialog({
    kind: 'toggle',
    title: `${next}${noun}`,
    submit: `确认${next}`,
    cancelText: '取消',
    bodyText: `将${next}「${name}」。请确认该对象和目标状态。`,
    detail: kind === 'levels'
      ? '等级状态只表达该规则是否参与成长值匹配；停用不改写已按原规则结算的存量记录。'
      : '目录状态决定会员能否兑换该对象；已有兑换与交易记录保留，停用不影响存量券。',
    caption: '',
    nextCaption: '',
    index
  })
}

function openDialog(partial: Omit<DialogState, 'error' | 'busy'>): void {
  dialog.value = { ...partial, error: '', busy: false }
  void nextTick(() => dialogEl.value?.showModal())
}

function setDialogError(message: string): void {
  if (dialog.value) dialog.value = { ...dialog.value, error: message }
}

function closeDialog(): void {
  dialogEl.value?.close()
}

/** 提交路径：关闭 → 重渲染 → 恢复焦点（B3-T68/B3-T72 的收尾顺序）。 */
async function finishDialog(message: string): Promise<void> {
  restoring.value = true
  closeDialog()
  await nextTick()
  await bootstrap()
  app.showToast(message)
  restoring.value = false
  restoreTriggerFocus()
}

function confirmDialog(): void {
  if (!dialog.value || dialog.value.busy) return
  const kind = dialog.value.kind
  if (kind === 'account' || kind === 'toggle') {
    if (kind === 'toggle') void submitToggle()
    else closeDialog()
    return
  }
  if (kind === 'level') void submitLevel()
  else if (kind === 'catalog') void submitCatalog()
  else if (kind === 'publish') void submitPublish()
  else if (kind === 'exception') void submitException()
}

async function submitLevel(): Promise<void> {
  if (!levelForm.levelName.trim() || !levelForm.levelCode.trim() || !levelForm.benefitText.trim()) {
    setDialogError('等级名称、等级编码与会员权益说明都不能为空。')
    return
  }
  if (!levelForm.ruleVersion.trim()) {
    setDialogError('规则版本不能为空；留空会被服务端改写为默认版本。')
    return
  }
  if (!(levelForm.discountRate > 0) || levelForm.discountRate > 1) {
    setDialogError('折扣率应大于 0 且不超过 1。')
    return
  }
  const index = dialog.value?.index ?? -1
  const payload = {
    levelCode: levelForm.levelCode.trim(),
    levelName: levelForm.levelName.trim(),
    growthThreshold: Math.max(0, Math.trunc(levelForm.growthThreshold || 0)),
    discountRate: levelForm.discountRate,
    benefitText: levelForm.benefitText.trim(),
    ruleVersion: levelForm.ruleVersion.trim(),
    status: index >= 0 ? levels.value[index]?.status : LEVEL_STATUS_ACTIVE
  }
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    if (levelForm.id != null) await adminMemberApi.updateLevel(levelForm.id, payload, isolated)
    else await adminMemberApi.createLevel(payload, isolated)
    await finishDialog('等级规则已保存。')
  } catch (error) {
    setDialogError(readableError(error, '等级规则保存失败，请核对条件后重试。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

async function submitToggle(): Promise<void> {
  const target = dialog.value?.index ?? -1
  const isLevel = /会员等级/.test(dialog.value?.title ?? '')
  const next = /^停用/.test(dialog.value?.title ?? '') ? 'INACTIVE' : 'ACTIVE'
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    if (isLevel) {
      const level = levels.value[target]
      await adminMemberApi.updateLevelStatus(level.id, next === 'ACTIVE' ? LEVEL_STATUS_ACTIVE : LEVEL_STATUS_DISABLED, isolated)
    } else {
      await adminMemberRewardApi.updateCatalogItemStatus(catalogItems.value[target].itemId, next, isolated)
    }
    await finishDialog(isLevel ? '等级状态已更新。' : '目录状态已更新。')
  } catch (error) {
    setDialogError(readableError(error, '状态更新失败，请核对对象当前状态后重试。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

async function submitCatalog(): Promise<void> {
  if (!catalogForm.itemName.trim() || !catalogForm.itemCode.trim()) {
    setDialogError('目录名称与目录编码都不能为空。')
    return
  }
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    await adminMemberRewardApi.createCatalogItem({
      itemCode: catalogForm.itemCode.trim(),
      itemName: catalogForm.itemName.trim(),
      descriptionText: catalogForm.descriptionText.trim() || undefined
    }, isolated)
    await finishDialog('目录已登记，处于草稿状态，待发布首个版本。')
  } catch (error) {
    setDialogError(readableError(error, '目录登记失败，请核对编码是否重复。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

async function submitPublish(): Promise<void> {
  if (!versionForm.couponTemplateId) {
    setDialogError('请选择可发放的优惠券模板。')
    return
  }
  if (!versionForm.minimumLevelCode) {
    setDialogError('请选择最低会员等级。')
    return
  }
  if (!(versionForm.pointsCost > 0)) {
    setDialogError('积分成本必须大于 0。')
    return
  }
  if (versionForm.effectiveStartTime && versionForm.effectiveEndTime
    && versionForm.effectiveEndTime <= versionForm.effectiveStartTime) {
    setDialogError('兑换版本结束时间必须晚于开始时间。')
    return
  }
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    await adminMemberRewardApi.publishVersion(versionForm.itemId, {
      pointsCost: Math.trunc(versionForm.pointsCost),
      minimumLevelCode: versionForm.minimumLevelCode,
      couponTemplateId: versionForm.couponTemplateId,
      validityDays: Math.trunc(versionForm.validityDays || 1),
      effectiveStartTime: versionForm.effectiveStartTime || undefined,
      effectiveEndTime: versionForm.effectiveEndTime || undefined
    }, isolated)
    await finishDialog('目录新版本已发布。')
  } catch (error) {
    setDialogError(readableError(error, '版本发布失败，请核对券模板与生效窗口。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

async function submitException(): Promise<void> {
  const row = activeException.value
  if (!row) return
  const compensable = canCompensate(row.status, row.userCouponId)
  if (compensable && !compensateReason.value.trim()) {
    setDialogError('请先填写补偿原因。')
    return
  }
  dialog.value = { ...dialog.value!, busy: true, error: '' }
  try {
    if (compensable) await adminMemberRewardApi.compensate(row.id, compensateReason.value.trim(), isolated)
    else await adminMemberRewardApi.retry(row.id, isolated)
    await finishDialog(compensable ? '兑换补偿已完成。' : '原兑换重试已完成。')
  } catch (error) {
    setDialogError(readableError(error, '处理失败，请保留当前内容并重新核对状态。'))
    dialog.value = { ...dialog.value!, busy: false }
  }
}

/* ------------------------------------------------------------ 焦点与键盘 */

function rememberTrigger(event: Event | undefined, test: string): void {
  const node = (event?.currentTarget as HTMLElement | undefined) ?? (document.activeElement as HTMLElement | null)
  lastTrigger.value = node ?? null
  lastTriggerTest.value = test
}

function restoreTriggerFocus(): void {
  restoreFocusChain({
    rootSelector: '.sga-ops',
    trigger: lastTrigger.value,
    triggerTest: lastTriggerTest.value,
    tabSelector: `.sga-ops #tab-${view.value}`
  })
}

function onDialogClose(): void {
  if (restoring.value) return
  restoreTriggerFocus()
}

/** 原生模态的 Tab 环：第 6 次 Tab 会把焦点交给 body，这里显式夹在弹层内（B3-T61）。 */
function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab' || !dialogEl.value) return
  const nodes = [...dialogEl.value.querySelectorAll<HTMLElement>(
    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )].filter((node) => !node.hasAttribute('disabled') && node.offsetParent !== null)
  if (!nodes.length) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => route.name, () => {
  const next = initialView()
  if (next !== view.value) {
    view.value = next
    void bootstrap()
  }
})

onMounted(() => {
  void bootstrap()
  if (consumeTabFocus(view.value)) void nextTick(() => document.getElementById(`tab-${view.value}`)?.focus())
})

defineExpose({ view, surface, accounts, levels, catalogItems, exceptions, events, dialog })
</script>
