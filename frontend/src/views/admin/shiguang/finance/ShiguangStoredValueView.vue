<template>
  <div ref="rootEl" class="sga-fin" data-test="sga-sv-root">
    <header class="h-title">
      <div>
        <p class="eyebrow">平台财务 / CNY</p>
        <h1>支付与储值财务</h1>
        <p>从每笔资金的来源开始，核对证据、解释差异，按职责完成处理。</p>
      </div>
      <div>
        <span class="badge">{{ scopeBadge }}</span>
        <p class="h-caption">当前主体：{{ actorName }}</p>
      </div>
    </header>

    <nav class="h-nav" aria-label="财务工作面">
      <button
        v-for="tab in visibleTabs"
        :key="tab.key"
        type="button"
        :data-test="`sv-tab-${tab.key}`"
        :aria-pressed="view === tab.key"
        @click="go(tab)"
      >{{ tab.label }}</button>
    </nav>

    <div aria-live="polite">
      <!-- 无叶子权限（入口守卫之外的第二道正文防线） -->
      <section v-if="denied" class="empty" data-test="sv-denied">
        <h2>没有财务访问权限</h2>
        <p>当前主体不能查看支付与储值数据。请联系平台管理员核对授权。</p>
        <RouterLink class="btn" :to="{ name: 'SystemAdminDashboard' }">返回平台管理</RouterLink>
      </section>

      <template v-else>
        <section v-if="surface === 'loading'" class="h-loading" role="status" data-test="sv-loading">
          <h2>正在读取财务记录</h2>
          <p>请稍候，正在核对所选范围。</p>
          <progress aria-label="正在加载"></progress>
        </section>

        <section v-else-if="surface === 'error'" class="empty" role="alert" data-test="sv-error">
          <h2>暂时无法读取记录</h2>
          <p>{{ loadError || '尚未取得本次查询结果。已提交的资金动作不会因此重发。' }}</p>
          <button class="btn primary" type="button" data-test="sv-reload" @click="reload">重新查询</button>
        </section>

        <!-- 储值账户：四桶卡片 + 分账账本 + 原桶说明（B5-H8/H9/H10/H11/H13） -->
        <div v-else-if="view === 'accounts'" class="h-columns">
          <aside class="h-rail" aria-label="储值账户">
            <h2>储值账户</h2>
            <p class="h-caption">{{ accountRailCaption }}</p>
            <button
              v-for="account in accounts"
              :key="account.accountRef"
              class="h-pick"
              type="button"
              :data-test="`sv-pick-${account.accountRef}`"
              :aria-pressed="selectedAccount?.accountRef === account.accountRef"
              @click="selectAccount(account)"
            >
              <strong>账户 {{ shortRef(account.accountRef) }}</strong>
              <small>可用 {{ money(account.availableTotal) }} · {{ account.userRef }}</small>
              <span class="badge" :class="accountBadgeTone(account.status)">{{ accountStatusLabel(account.status) }}</span>
            </button>
            <div v-if="accountTotal > accounts.length || accountPage > 1" class="fin-rail-pager" data-test="sv-account-pager">
              <button class="btn" type="button" :disabled="accountPage <= 1" @click="turnAccountPage(-1)">上一页</button>
              <button class="btn" type="button" :disabled="accountPage * pageSize >= accountTotal" @click="turnAccountPage(1)">下一页</button>
            </div>
          </aside>

          <article v-if="selectedAccount" class="h-detail">
            <div class="h-detail-head">
              <div>
                <p class="eyebrow">{{ selectedAccount.userRef }} / CNY</p>
                <h2>账户 {{ shortRef(selectedAccount.accountRef) }}</h2>
                <span class="badge" :class="accountBadgeTone(selectedAccount.status)">{{ accountStatusLabel(selectedAccount.status) }}</span>
              </div>
              <div>
                <p class="h-caption">账户总额</p>
                <div class="h-money">{{ money(accountTotalBalance) }}</div>
              </div>
            </div>

            <div class="h-buckets">
              <section class="h-bucket">
                <h3>本金</h3>
                <div class="h-bucket-total">{{ money(Number(selectedAccount.principalAvailable) + Number(selectedAccount.principalFrozen)) }}</div>
                <div><span>可用</span><span>{{ money(selectedAccount.principalAvailable) }}</span></div>
                <div><span>冻结</span><span>{{ money(selectedAccount.principalFrozen) }}</span></div>
              </section>
              <section class="h-bucket">
                <h3>赠送金</h3>
                <div class="h-bucket-total">{{ money(Number(selectedAccount.bonusAvailable) + Number(selectedAccount.bonusFrozen)) }}</div>
                <div><span>可用</span><span>{{ money(selectedAccount.bonusAvailable) }}</span></div>
                <div><span>冻结</span><span>{{ money(selectedAccount.bonusFrozen) }}</span></div>
              </section>
            </div>

            <div class="h-actions">
              <button
                v-if="selectedAccount.status === 'NORMAL' && canFreezeAccount"
                class="btn"
                type="button"
                data-test="sv-freeze"
                @click="openDialog('freeze')"
              >冻结账户</button>
              <button
                v-if="selectedAccount.status === 'FROZEN' && canUnfreezeAccount"
                class="btn primary"
                type="button"
                data-test="sv-unfreeze"
                @click="openDialog('unfreeze')"
              >申请解冻</button>
              <button
                v-if="selectedAccount.status === 'NORMAL' && canCloseAccount"
                class="btn"
                type="button"
                data-test="sv-begin-close"
                @click="openDialog('begin-close')"
              >开始注销</button>
              <button
                v-if="selectedAccount.status === 'CLOSING' && canCloseAccount"
                class="btn"
                type="button"
                data-test="sv-close"
                @click="openDialog('close-account')"
              >完成注销</button>
              <button
                v-if="selectedAccount.status !== 'CLOSED'"
                class="btn"
                type="button"
                data-test="sv-order-facts"
                @click="openDialog('order-facts')"
              >查询原桶说明</button>
            </div>
            <p v-if="selectedAccount.status === 'FROZEN'" class="h-audit-note">
              冻结金额不能消费。解冻通过审批后按原冻结记录恢复分桶。
            </p>

            <!-- 原桶订单退款说明（B5-H11）：只读分摊摘要，订单退款审核由场馆履约承接 -->
            <section v-if="accountFacts" class="notice" data-test="sv-order-facts-result">
              <h3>订单原桶说明 · {{ accountFacts.orderNo }}</h3>
              <p v-if="accountFacts.payment">
                原支付 …{{ accountFacts.payment.paymentNoSuffix }} · {{ money(accountFacts.payment.amount) }}<br>
                原支付：本金 {{ money(accountFacts.payment.principalAmount) }} + 赠送金 {{ money(accountFacts.payment.bonusAmount) }} · 状态 {{ accountFacts.payment.status }}
              </p>
              <p v-if="accountFacts.refund">
                退款 …{{ accountFacts.refund.refundNoSuffix }} · {{ money(accountFacts.refund.amount) }}<br>
                本金 {{ money(accountFacts.refund.principalAmount) }} + 赠送金 {{ money(accountFacts.refund.bonusAmount) }}<template v-if="Number(accountFacts.refund.compensationAmount) > 0"> + 过期补偿 {{ money(accountFacts.refund.compensationAmount) }}</template> · 状态 {{ accountFacts.refund.status }}
              </p>
              <p v-if="!accountFacts.payment && !accountFacts.refund">该订单没有储值支付事实。</p>
              <div class="h-actions">
                <RouterLink class="btn" :to="{ name: 'VenueAdminOrders' }">前往场馆退款审核</RouterLink>
              </div>
            </section>

            <section class="h-ledger-section">
              <h3>分账账本</h3>
              <p class="h-caption">保留原始交易，后续处理追加记录。借 / 贷按各分桶展示。</p>
              <div class="h-ledger" data-test="sv-ledger">
                <details v-for="group in ledgerGroups" :key="group.transactionRef" :open="ledgerGroups.length <= 3">
                  <summary>
                    <strong>{{ group.title }}</strong>
                    <span>{{ shortTime(group.occurredAt) }} · 展开分录</span>
                  </summary>
                  <div class="h-lines">
                    <div v-for="(line, index) in group.lines" :key="index">
                      <span>{{ line.bucketLabel }}</span>
                      <span>{{ line.directionLabel }}</span>
                      <b>{{ money(line.amount) }}</b>
                    </div>
                  </div>
                  <p class="h-caption">{{ group.reason }}<br>{{ group.sourceTypeLabel }} / {{ group.sourceRef }}<br>交易引用 {{ group.transactionRef }}</p>
                </details>
                <p v-if="!ledgerGroups.length" class="muted">当前账户尚无账本记录。</p>
              </div>
            </section>
            <p class="h-audit-note">
              账户引用 {{ selectedAccount.accountRef }} · 产品 {{ selectedAccount.productCode }} · 更新于 {{ shortTime(selectedAccount.updateTime) }}
            </p>
          </article>
        </div>

        <!-- 充值处理：证据段 + 异常/结果弹层 + 预计/实际赠送（B5-H14/H15/H16） -->
        <div v-else-if="view === 'recharges'" class="h-columns">
          <aside class="h-rail" aria-label="充值与异常">
            <h2>充值与异常</h2>
            <p class="h-caption">{{ rechargeRailCaption }}</p>
            <button
              v-for="recharge in recharges"
              :key="recharge.rechargeRef"
              class="h-pick"
              type="button"
              :data-test="`sv-recharge-${recharge.rechargeRef}`"
              :aria-pressed="selectedRecharge?.rechargeRef === recharge.rechargeRef"
              @click="selectRecharge(recharge)"
            >
              <strong>充值 …{{ recharge.rechargeNoSuffix }}</strong>
              <small>{{ shortTime(recharge.createTime) }} · 本金 {{ money(recharge.principalAmount) }}</small>
              <span class="badge" :class="rechargeBadgeTone(recharge.status)">{{ rechargeStatusLabel(recharge.status) }}</span>
            </button>
            <div v-if="rechargeTotal > recharges.length || rechargePage > 1" class="fin-rail-pager" data-test="sv-recharge-pager">
              <button class="btn" type="button" :disabled="rechargePage <= 1" @click="turnRechargePage(-1)">上一页</button>
              <button class="btn" type="button" :disabled="rechargePage * pageSize >= rechargeTotal" @click="turnRechargePage(1)">下一页</button>
            </div>
          </aside>

          <article v-if="selectedRecharge" class="h-detail">
            <div class="h-detail-head">
              <div>
                <p class="eyebrow">{{ selectedRecharge.userRef }} / {{ selectedRecharge.productCode }}</p>
                <h2>充值 …{{ selectedRecharge.rechargeNoSuffix }}</h2>
                <span class="badge" :class="rechargeBadgeTone(selectedRecharge.status)">{{ rechargeStatusLabel(selectedRecharge.status) }}</span>
              </div>
              <div class="h-money">{{ money(selectedRecharge.principalAmount) }}<br><small>充值本金</small></div>
            </div>
            <dl class="h-meta">
              <div><dt>预计 / 实际赠送</dt><dd>{{ money(selectedRecharge.expectedBonusAmount) }} / {{ postedRecharge ? money(selectedRecharge.actualBonusAmount) : money(0) }}</dd></div>
              <div><dt>环境 / 政策</dt><dd>{{ selectedRecharge.providerEnv }} / {{ selectedRecharge.productCode }}</dd></div>
              <div><dt>Provider</dt><dd>{{ selectedRecharge.providerName }}</dd></div>
              <div><dt>充值引用</dt><dd><span class="h-code">{{ selectedRecharge.rechargeRef }}</span></dd></div>
            </dl>
            <div class="h-evidence">
              <section>
                <h3>渠道结果</h3>
                <p>{{ channelOutcomeLine }}</p>
              </section>
              <section>
                <h3>本地入账</h3>
                <p>{{ localPostingLine }}</p>
                <p v-if="selectedRecharge.failureCode" class="h-code">{{ selectedRecharge.failureCode }}</p>
              </section>
              <section>
                <h3>下一步处理</h3>
                <div class="h-actions">
                  <button
                    v-if="canRecoverRecharge && ['PROVIDER_SUCCEEDED_PENDING_POSTING', 'POSTING'].includes(selectedRecharge.status)"
                    class="btn primary"
                    type="button"
                    data-test="sv-request-recovery"
                    @click="openDialog('posting-recovery')"
                  >申请待入账恢复</button>
                  <button
                    v-if="canRefundRecharge && selectedRecharge.status === 'SUCCEEDED'"
                    class="btn"
                    type="button"
                    data-test="sv-request-refund"
                    @click="openDialog('recharge-refund')"
                  >申请充值全额退款</button>
                  <button
                    v-if="canDisputeRecharge && ['SUCCEEDED', 'REFUNDING'].includes(selectedRecharge.status)"
                    class="btn"
                    type="button"
                    data-test="sv-dispute"
                    @click="openDialog('dispute')"
                  >登记渠道争议</button>
                  <button
                    v-if="selectedRecharge.status === 'MANUAL_REVIEW'"
                    class="btn"
                    type="button"
                    data-test="sv-recharge-evidence"
                    @click="openDialog('recharge-evidence')"
                  >查看异常证据</button>
                  <button
                    v-if="selectedRecharge.status === 'REFUNDING'"
                    class="btn"
                    type="button"
                    data-test="sv-recharge-result"
                    @click="openDialog('recharge-result')"
                  >查询当前结果</button>
                </div>
                <button class="btn" type="button" data-test="sv-goto-account" @click="gotoAccountOfRecharge">查看账户分账</button>
              </section>
            </div>
            <p class="h-audit-note">充值退款处理的是原充值资金及关联赠送金。订单退款申请由场馆履约审核承接。</p>
          </article>
        </div>

        <!-- 审批工作台：按编号查询 + 三步职责轨道（B5-H17/H18/H19/H20） -->
        <template v-else-if="view === 'approvals'">
          <div v-if="!canViewApprovals" class="empty" data-test="sv-approvals-denied">
            <h2>当前主体不能查看审批事项</h2>
            <p>财务查看权限不包含储值审批。需要对应动作的复核或执行权限。</p>
          </div>
          <template v-else>
            <div class="h-section-title">
              <div>
                <h2>先核对，再分别负责</h2>
                <p class="muted">申请与复核不能由同一人完成；复核人与执行人必须不同。</p>
              </div>
              <button class="btn" type="button" data-test="sv-lookup-approval" @click="openDialog('approval-lookup')">按编号查询</button>
            </div>
            <div class="h-columns">
              <aside class="h-rail" aria-label="待办审批">
                <h2>待办审批</h2>
                <p class="h-caption">全部类型 · 共 {{ approvalTotal }} 条</p>
                <button
                  v-for="approval in approvals"
                  :key="approval.approvalNo"
                  class="h-pick"
                  type="button"
                  :data-test="`sv-approval-${approval.approvalNo}`"
                  :aria-pressed="selectedApproval?.approvalNo === approval.approvalNo"
                  @click="selectApproval(approval)"
                >
                  <strong>{{ approvalActionLabel(approval.actionType) }}</strong>
                  <small>{{ approval.approvalNo }} · {{ approval.requesterRef }}申请</small>
                  <span class="badge" :class="approvalBadgeTone(approval.status)">{{ approvalStatusLabel(approval.status) }}</span>
                </button>
                <button
                  v-if="pinnedApproval"
                  class="h-pick"
                  type="button"
                  :data-test="`sv-approval-${pinnedApproval.approvalNo}`"
                  :aria-pressed="true"
                >
                  <strong>{{ approvalActionLabel(pinnedApproval.actionType) }}</strong>
                  <small>{{ pinnedApproval.approvalNo }} · 终态查询</small>
                  <span class="badge" :class="approvalBadgeTone(pinnedApproval.status)">{{ approvalStatusLabel(pinnedApproval.status) }}</span>
                </button>
                <div v-if="approvalTotal > approvals.length" class="fin-rail-pager" data-test="sv-approval-pager">
                  <button class="btn" type="button" :disabled="approvalPage <= 1" @click="turnApprovalPage(-1)">上一页</button>
                  <button class="btn" type="button" :disabled="approvalPage * pageSize >= approvalTotal" @click="turnApprovalPage(1)">下一页</button>
                </div>
              </aside>

              <article v-if="selectedApproval" class="h-detail" :data-test="`sv-approval-detail-${selectedApproval.approvalNo}`">
                <div class="h-detail-head">
                  <div>
                    <p class="eyebrow">{{ selectedApproval.approvalNo }}</p>
                    <h2>{{ approvalActionLabel(selectedApproval.actionType) }}</h2>
                    <span class="badge" :class="approvalBadgeTone(selectedApproval.status)">{{ approvalStatusLabel(selectedApproval.status) }}</span>
                  </div>
                  <div v-if="selectedApproval.amount != null" class="h-money">{{ money(selectedApproval.amount) }}</div>
                </div>

                <div class="h-approval-track">
                  <section class="h-step">
                    <small>01 / 申请</small>
                    <strong>{{ selectedApproval.requesterRef }}</strong>
                    <p>{{ shortTime(selectedApproval.createTime) }}</p>
                  </section>
                  <section class="h-step" :class="{ current: selectedApproval.status === 'PENDING' }">
                    <small>02 / 独立复核</small>
                    <strong>{{ selectedApproval.reviewerRef || '等待另一主体' }}</strong>
                    <p>{{ selectedApproval.reviewedAt ? shortTime(selectedApproval.reviewedAt) : '申请人不能自审' }}</p>
                  </section>
                  <section class="h-step" :class="{ current: ['APPROVED', 'EXECUTING'].includes(selectedApproval.status) }">
                    <small>03 / 执行</small>
                    <strong>{{ selectedApproval.executorRef || '由具备权限的主体执行' }}</strong>
                    <p>{{ selectedApproval.executedAt ? shortTime(selectedApproval.executedAt) : '不能由本次复核人执行' }}</p>
                  </section>
                </div>

                <h3>申请事实</h3>
                <p>{{ selectedApproval.reason || '—' }}</p>
                <dl class="h-meta">
                  <div><dt>目标引用</dt><dd><span class="h-code">{{ selectedApproval.targetRef }}</span></dd></div>
                  <div><dt>审批有效至</dt><dd>{{ selectedApproval.expiresAt }}</dd></div>
                  <div><dt>政策版本</dt><dd>{{ selectedApproval.policyVersion || '—' }}</dd></div>
                  <div><dt>当前主体与申请人的关系</dt><dd>{{ selectedApproval.requesterIsCurrentActor ? '本人提出' : '他人提出' }}</dd></div>
                </dl>
                <template v-if="selectedApproval.reviewReason">
                  <h3>复核说明</h3>
                  <p>{{ selectedApproval.reviewReason }}</p>
                </template>
                <div v-if="selectedApproval.executionResult" class="notice">
                  <h3>执行结果</h3>
                  <p>{{ selectedApproval.executionResult }}</p>
                </div>

                <template v-if="selectedApproval.status === 'PENDING'">
                  <p class="h-audit-note">{{ selectedApproval.requesterIsCurrentActor
                    ? '你是本次申请人，不能批准或拒绝自己的申请。可以撤回，或交由另一主体复核。'
                    : '核对目标、金额与原因。批准仅授予一次执行资格，不代表资金处理已经完成。' }}</p>
                  <div class="h-actions">
                    <button
                      class="btn primary"
                      type="button"
                      data-test="sv-approve"
                      :disabled="!canReviewSelected || selectedApproval.requesterIsCurrentActor"
                      @click="openDialog('approve')"
                    >批准申请</button>
                    <button
                      class="btn"
                      type="button"
                      data-test="sv-reject"
                      :disabled="!canReviewSelected || selectedApproval.requesterIsCurrentActor"
                      @click="openDialog('reject')"
                    >拒绝申请</button>
                    <button
                      v-if="canRevokeSelected"
                      class="btn"
                      type="button"
                      data-test="sv-revoke"
                      @click="openDialog('revoke')"
                    >撤回申请</button>
                  </div>
                </template>
                <template v-else-if="selectedApproval.status === 'APPROVED'">
                  <p class="h-audit-note">执行前将再次校验有效期、权限和目标快照。</p>
                  <div class="h-actions">
                    <button
                      class="btn primary"
                      type="button"
                      data-test="sv-execute"
                      :disabled="!canExecuteSelected"
                      @click="executeSelected"
                    >执行已批准事项</button>
                    <button
                      v-if="canRevokeSelected"
                      class="btn"
                      type="button"
                      data-test="sv-revoke"
                      @click="openDialog('revoke')"
                    >撤回申请</button>
                  </div>
                </template>
                <div v-else-if="selectedApproval.status === 'EXECUTING'" class="notice" role="status">
                  已取得执行权，正在处理。请勿重复提交。
                </div>
                <div v-if="selectedApproval.status === 'CONSUMED'" class="h-actions">
                  <button class="btn" type="button" data-test="sv-goto-target" @click="gotoTargetOfApproval">查看处理后的目标</button>
                </div>
              </article>
              <article v-else class="h-detail">
                <div class="empty"><h2>暂无审批</h2><p>从账户、充值或对账差异提出申请。</p></div>
              </article>
            </div>
          </template>
        </template>

        <!-- 产品与政策：控制清单 + 政策快照 + DEV 核验（B5-H12/H21/H22） -->
        <template v-else-if="view === 'products'">
          <div class="h-section-title">
            <div>
              <h2>产品与既有政策</h2>
              <p class="muted">按产品控制充值与消费入口，金额规则沿用已存政策。</p>
            </div>
            <button
              v-if="canManagePolicy"
              class="btn"
              type="button"
              data-test="sv-create-product"
              @click="openDialog('create-product')"
            >创建 DEV 产品</button>
          </div>
          <section v-for="product in products" :key="product.id" class="h-product" :data-test="`sv-product-${product.productCode}`">
            <div>
              <p class="eyebrow">{{ product.environment }} / CNY</p>
              <h3>{{ product.productName }}</h3>
              <p class="h-code">{{ product.productCode }} · {{ product.policyVersion }}</p>
              <dl class="h-meta">
                <div><dt>产品状态</dt><dd>{{ product.status === 'ENABLED' ? '启用' : product.status === 'DISABLED' ? '停用' : '草稿' }}</dd></div>
                <div><dt>政策版本</dt><dd>{{ product.policyVersion }}</dd></div>
              </dl>
              <div class="h-actions">
                <button class="btn" type="button" :data-test="`sv-policy-${product.productCode}`" @click="openPolicyDialog(product)">查看政策快照</button>
                <button
                  v-if="canManagePolicy && product.environment === 'DEV'"
                  class="btn"
                  type="button"
                  :data-test="`sv-identity-${product.productCode}`"
                  @click="openIdentityDialog(product)"
                >DEV 身份核验</button>
              </div>
            </div>
            <div>
              <div class="h-product-controls">
                <span>允许充值</span>
                <button
                  class="btn"
                  type="button"
                  :data-test="`sv-toggle-recharge-${product.productCode}`"
                  :aria-pressed="product.rechargeEnabled"
                  :disabled="!canManagePolicy || busyProductId === product.id"
                  @click="openToggleDialog(product, 'recharge')"
                >{{ product.rechargeEnabled ? '已开启' : '已关闭' }}</button>
              </div>
              <div class="h-product-controls">
                <span>允许消费</span>
                <button
                  class="btn"
                  type="button"
                  :data-test="`sv-toggle-spend-${product.productCode}`"
                  :aria-pressed="product.spendEnabled"
                  :disabled="!canManagePolicy || busyProductId === product.id"
                  @click="openToggleDialog(product, 'spend')"
                >{{ product.spendEnabled ? '已开启' : '已关闭' }}</button>
              </div>
              <div class="h-product-controls">
                <span>产品启用状态</span>
                <button
                  class="btn"
                  type="button"
                  :data-test="`sv-toggle-status-${product.productCode}`"
                  :disabled="!canManagePolicy || busyProductId === product.id"
                  @click="openToggleDialog(product, 'status')"
                >{{ product.status === 'ENABLED' ? '停用产品' : '启用产品' }}</button>
              </div>
              <p class="h-caption">关闭入口不会重写已经发生的资金事实。</p>
            </div>
          </section>
        </template>

        <!-- 对账差异（储值）：运行记录 + 差异 + 申请本金调账（B5-H6 储值侧） -->
        <template v-else>
          <div class="h-section-title">
            <div>
              <h2>把差异留在事实旁</h2>
              <p class="muted">先确认日期，再选择对应的处理方式。</p>
            </div>
            <button
              v-if="canRunReconciliation"
              class="btn"
              type="button"
              data-test="sv-run-reconcile"
              @click="openDialog('run')"
            >运行对账</button>
          </div>
          <div class="h-columns">
            <aside class="h-rail" aria-label="储值差异">
              <h2>储值差异</h2>
              <p class="h-caption">{{ storedRailCaption }}</p>
              <button
                v-for="difference in differences"
                :key="difference.differenceRef"
                class="h-pick"
                type="button"
                :data-test="`sv-diff-${difference.differenceRef}`"
                :aria-pressed="selectedDifference?.differenceRef === difference.differenceRef"
                @click="selectedDifference = difference"
              >
                <strong>{{ difference.differenceRef }}</strong>
                <small>{{ differenceTypeText(difference.differenceType) }} · {{ shortTime(difference.createTime) }}</small>
                <span class="badge" :class="storedBadgeTone(difference.status)">{{ storedStatusLabel(difference.status) }}</span>
              </button>
              <div v-if="differenceTotal > differences.length" class="fin-rail-pager" data-test="sv-diff-pager">
                <button class="btn" type="button" :disabled="differencePage <= 1" @click="turnDifferencePage(-1)">上一页</button>
                <button class="btn" type="button" :disabled="differencePage * pageSize >= differenceTotal" @click="turnDifferencePage(1)">下一页</button>
              </div>
            </aside>

            <article v-if="selectedDifference" class="h-detail">
              <div class="h-detail-head">
                <div>
                  <p class="eyebrow">储值差异 · {{ shortTime(selectedDifference.createTime) }}</p>
                  <h2>{{ selectedDifference.differenceRef }}</h2>
                  <span class="badge" :class="storedBadgeTone(selectedDifference.status)">{{ storedStatusLabel(selectedDifference.status) }}</span>
                </div>
              </div>
              <div class="h-compare">
                <div><p>本地本金</p><strong>{{ money(selectedDifference.localAmount) }}</strong></div>
                <div><p>渠道记录</p><strong>{{ money(selectedDifference.providerAmount) }}</strong></div>
                <div><p>渠道 − 本地</p><strong>{{ money(storedChannelMinusLocal) }}</strong></div>
              </div>
              <h3>核对说明</h3>
              <p>{{ selectedDifference.reason || '—' }}</p>
              <p v-if="selectedDifference.ledgerTransactionRef" class="h-caption">
                调账流水 <span class="h-code">{{ selectedDifference.ledgerTransactionRef }}</span>
              </p>
              <div v-if="selectedDifference.status === 'RESOLVED'" class="notice">
                差异已完成审批执行，并追加了本金调账流水。<br>历史记录保留，可到储值账户查看。
              </div>
              <div class="h-actions">
                <button
                  class="btn primary"
                  type="button"
                  data-test="sv-request-adjustment"
                  :disabled="!canRequestAdjustment || !['OPEN', 'UNDER_REVIEW'].includes(selectedDifference.status)"
                  @click="openDialog('request-adjustment')"
                >申请本金调账</button>
              </div>
              <p class="h-audit-note">储值调账须独立复核后执行，仅追加与当前差额一致的本金账本。</p>
            </article>
            <article v-else class="h-detail">
              <div class="empty"><h2>当前范围没有差异</h2><p>运行对账会生成新的账实核对结果，不自动平账。</p></div>
            </article>
          </div>
        </template>
      </template>
    </div>

    <!-- 弹层（原生 dialog，参考 h-dialog 结构） -->
    <dialog ref="dialogEl" class="h-dialog" @close="focusBack">
      <div class="h-dialog-top">
        <h2 id="dialog-title">{{ dialogTitle }}</h2>
        <button type="button" class="btn" @click="closeDialog">关闭</button>
      </div>

      <!-- 冻结（B5-H10/H13：业务依据编号必填 + 转桶 notice） -->
      <form v-if="dialogKind === 'freeze' && selectedAccount" @submit.prevent="submitAccountAction('freeze')">
        <p>账户 {{ shortRef(selectedAccount.accountRef) }} · {{ selectedAccount.userRef }}</p>
        <div class="notice">
          本金可用 {{ money(selectedAccount.principalAvailable) }} 与赠送金可用 {{ money(selectedAccount.bonusAvailable) }}
          将分别转入对应冻结桶，账户总额保持不变。
        </div>
        <label class="field"><span>业务依据编号</span><input v-model="form.businessRef" required maxlength="96" data-test="sv-business-ref"></label>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-freeze-submit" :disabled="dialogBusy">确认冻结</button>
        </footer>
      </form>

      <!-- 申请解冻（生成审批） -->
      <form v-else-if="dialogKind === 'unfreeze' && selectedAccount" @submit.prevent="submitAccountAction('unfreeze')">
        <p>账户 {{ shortRef(selectedAccount.accountRef) }} · 当前已冻结</p>
        <p>原冻结本金 {{ money(selectedAccount.principalFrozen) }} / 赠送金 {{ money(selectedAccount.bonusFrozen) }}。申请提交后余额不会立即恢复可用。</p>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-unfreeze-submit" :disabled="dialogBusy">提交复核</button>
        </footer>
      </form>

      <!-- 开始注销 / 完成注销（B5-H10：业务依据编号必填；资格由服务端终审） -->
      <form v-else-if="(dialogKind === 'begin-close' || dialogKind === 'close-account') && selectedAccount" @submit.prevent="submitAccountAction(dialogKind)">
        <p>账户 {{ shortRef(selectedAccount.accountRef) }}</p>
        <div class="notice">{{ dialogKind === 'begin-close'
          ? '注销前需核对零余额、无在途充值、退款及争议；服务端会再次校验。不会自动清空余额或删除账本。'
          : '完成注销将按已核对的零余额事实关闭账户入口，历史账本保留。' }}</div>
        <label class="field"><span>业务依据编号</span><input v-model="form.businessRef" required maxlength="96" data-test="sv-business-ref"></label>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" :data-test="dialogKind === 'begin-close' ? 'sv-begin-close-submit' : 'sv-close-submit'" :disabled="dialogBusy">
            {{ dialogKind === 'begin-close' ? '进入注销中' : '确认注销' }}
          </button>
        </footer>
      </form>

      <!-- 原桶说明查询（B5-H11） -->
      <form v-else-if="dialogKind === 'order-facts'" @submit.prevent="submitOrderFacts">
        <div class="notice">按订单号读取储值原桶支付/退款分摊摘要。订单退款审核由场馆履约承接，这里不提供退款执行。</div>
        <label class="field"><span>订单号</span><input v-model="form.orderNo" required maxlength="64" placeholder="ORD…" data-test="sv-order-no"></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-order-facts-submit" :disabled="dialogBusy">查询</button>
        </footer>
      </form>

      <!-- 待入账恢复申请 -->
      <form v-else-if="dialogKind === 'posting-recovery' && selectedRecharge" @submit.prevent="submitRechargeApproval('posting-recovery')">
        <p>充值 …{{ selectedRecharge.rechargeNoSuffix }} / {{ selectedRecharge.rechargeRef }}</p>
        <div class="notice">渠道成功，本地尚未完成入账。拟恢复本金 {{ money(selectedRecharge.principalAmount) }}、赠送金 {{ postedRecharge ? money(selectedRecharge.actualBonusAmount) : money(0) }}。批准后仍需独立执行。</div>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-recovery-submit" :disabled="dialogBusy">提交复核</button>
        </footer>
      </form>

      <!-- 充值全额退款申请 -->
      <form v-else-if="dialogKind === 'recharge-refund' && selectedRecharge" @submit.prevent="submitRechargeApproval('recharge-refund')">
        <p>充值 …{{ selectedRecharge.rechargeNoSuffix }}</p>
        <div class="notice">原充值本金 {{ money(selectedRecharge.principalAmount) }} 全额原路退回；关联未使用赠送金随退款冲销。<br>若本金不足或赠送金已消费，实际执行可能进入人工处置，不等同于审批失败。</div>
        <p class="h-caption">这不是订单退款审核。当前申请不会退款或改余额。</p>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-refund-submit" :disabled="dialogBusy">提交复核</button>
        </footer>
      </form>

      <!-- 渠道争议登记 -->
      <form v-else-if="dialogKind === 'dispute' && selectedRecharge" @submit.prevent="submitDispute">
        <p>充值 …{{ selectedRecharge.rechargeNoSuffix }} / {{ selectedRecharge.providerEnv }}</p>
        <div class="notice">登记后按现有规则先冻结可用本金，再冻结该充值关联的可用赠送金；不足部分保留争议事实。</div>
        <label class="field"><span>渠道争议编号</span><input v-model="form.caseId" required maxlength="96" data-test="sv-dispute-case"></label>
        <label class="field"><span>争议金额</span><input v-model="form.amount" type="number" min="0.01" step="0.01" required data-test="sv-dispute-amount"></label>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-dispute-submit" :disabled="dialogBusy">确认登记</button>
        </footer>
      </form>

      <!-- 充值异常证据（只读） -->
      <div v-else-if="dialogKind === 'recharge-evidence' && selectedRecharge">
        <p>充值 …{{ selectedRecharge.rechargeNoSuffix }}</p>
        <dl class="h-meta">
          <div><dt>当前状态</dt><dd>{{ rechargeStatusLabel(selectedRecharge.status) }}</dd></div>
          <div><dt>稳定错误码</dt><dd><span class="h-code">{{ selectedRecharge.failureCode || 'RECOVERY_REQUIRED' }}</span></dd></div>
          <div><dt>环境 / 渠道</dt><dd>{{ selectedRecharge.providerEnv }} / {{ selectedRecharge.providerName }}</dd></div>
          <div><dt>处理结论</dt><dd>自动资金动作不可用，需核对渠道与原账本</dd></div>
        </dl>
        <div class="notice">没有可确认的最新成功结果。当前不重复扣款、不自动恢复入账。</div>
        <div class="h-actions"><RouterLink class="btn" :to="{ name: 'SystemProviders' }">查看 Provider 观测</RouterLink></div>
        <footer><button type="button" class="btn" @click="closeDialog">返回</button></footer>
      </div>

      <!-- 退款结果仍待确认（只读） -->
      <div v-else-if="dialogKind === 'recharge-result' && selectedRecharge">
        <p>充值 …{{ selectedRecharge.rechargeNoSuffix }}</p>
        <span class="badge warn">{{ rechargeStatusLabel('REFUNDING') }}</span>
        <p>本次查询没有新的终态证据。原入账与待确认退款事实均保留。</p>
        <div class="notice">不能据此重复发起退款或把审批执行完成显示为资金到账。</div>
        <footer><button type="button" class="btn" @click="closeDialog">返回</button></footer>
      </div>

      <!-- 按编号查询审批（B5-H17，终态可达） -->
      <form v-else-if="dialogKind === 'approval-lookup'" @submit.prevent="submitApprovalLookup">
        <label class="field"><span>审批编号</span><input v-model="form.approvalNo" required maxlength="64" placeholder="SVA-…" data-test="sv-lookup-no"></label>
        <p class="h-caption">待办列表之外，终态审批也可以按编号查询。</p>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-lookup-submit" :disabled="dialogBusy">查询</button>
        </footer>
      </form>

      <!-- 批准 / 拒绝 / 撤回 -->
      <form v-else-if="['approve', 'reject', 'revoke'].includes(dialogKind || '') && selectedApproval" @submit.prevent="submitApprovalDecision(dialogKind)">
        <p>{{ approvalActionLabel(selectedApproval.actionType) }} · {{ selectedApproval.approvalNo }}</p>
        <div v-if="dialogKind === 'approve'" class="notice">批准仅授予一次执行资格，不代表资金处理已经完成。</div>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-action-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" :data-test="`sv-${dialogKind}-submit`" :disabled="dialogBusy">
            {{ dialogKind === 'approve' ? '批准申请' : dialogKind === 'reject' ? '拒绝申请' : '撤回申请' }}
          </button>
        </footer>
      </form>

      <!-- 储值对账：申请本金调账 -->
      <form v-else-if="dialogKind === 'request-adjustment' && selectedDifference" @submit.prevent="submitRequestAdjustment">
        <p>{{ selectedDifference.differenceRef }}</p>
        <div class="h-compare">
          <div><p>来源 · 渠道</p><strong>{{ money(selectedDifference.providerAmount) }}</strong></div>
          <div><p>来源 · 本地本金</p><strong>{{ money(selectedDifference.localAmount) }}</strong></div>
          <div><p>申请本金调整</p><strong>{{ money(storedChannelMinusLocal) }}</strong></div>
        </div>
        <div class="notice">赠送金调整：0.00 元。执行时追加本金与人工调账清算分录；原记录保留。</div>
        <label class="field"><span>申请金额（必须与差额一致）</span><input v-model="form.amount" type="number" step="0.01" required data-test="sv-adjust-amount"></label>
        <label class="field"><span>处理原因</span><textarea v-model="form.reason" rows="3" required maxlength="512" placeholder="说明核对依据和本次处理原因" data-test="sv-adjust-reason"></textarea></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-adjust-submit" :disabled="dialogBusy">提交复核</button>
        </footer>
      </form>

      <!-- 储值对账：运行 -->
      <form v-else-if="dialogKind === 'run'" @submit.prevent="submitRunReconciliation">
        <label class="field"><span>对账日期</span><input v-model="form.runDate" type="date" required data-test="sv-run-date"></label>
        <p class="h-caption">运行只生成账实核对结果，不自动平账。</p>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-run-submit" :disabled="dialogBusy">{{ dialogBusy ? '正在核对…' : '开始对账' }}</button>
        </footer>
      </form>

      <!-- 政策快照（B5-H12，只读） -->
      <div v-else-if="dialogKind === 'policy' && policyProduct">
        <p>{{ policyProduct.environment }} / CNY / {{ policyProduct.productCode }}</p>
        <dl class="h-meta">
          <div><dt>单笔充值</dt><dd>{{ money(policyProduct.minimumRecharge) }} – {{ money(policyProduct.maximumRecharge) }} 元</dd></div>
          <div><dt>自然日充值上限</dt><dd>{{ money(policyProduct.dailyRechargeLimit) }} 元</dd></div>
          <div><dt>本金 / 总余额上限</dt><dd>{{ money(policyProduct.principalBalanceLimit) }} / {{ money(policyProduct.totalBalanceLimit) }} 元</dd></div>
          <div><dt>赠送比例</dt><dd>{{ policyProduct.bonusRatePercent }}% · 按分向下取整</dd></div>
          <div><dt>单次 / 月赠送上限</dt><dd>{{ money(policyProduct.bonusPerRechargeLimit) }} / {{ money(policyProduct.monthlyBonusLimit) }} 元</dd></div>
          <div><dt>赠送金有效期</dt><dd>{{ policyProduct.bonusValidDays }} 天</dd></div>
          <div><dt>过期原桶退款补偿</dt><dd>{{ policyProduct.refundCompensationValidDays }} 天</dd></div>
        </dl>
        <p class="h-audit-note">只读快照，沿用已存金额规则。产品开关不编辑这些参数。</p>
        <footer><button type="button" class="btn" @click="closeDialog">返回</button></footer>
      </div>

      <!-- DEV 身份核验（B5-H22） -->
      <form v-else-if="dialogKind === 'identity' && identityProduct" @submit.prevent="submitIdentity">
        <div class="notice">仅用于 DEV 隔离产品的核验事实。请输入用户 ID 和证据摘要。</div>
        <label class="field"><span>用户 ID</span><input v-model="form.userId" type="number" min="1" step="1" required data-test="sv-identity-user"></label>
        <label class="field"><span>核验证据摘要</span><input v-model="form.digest" required maxlength="64" data-test="sv-identity-digest"></label>
        <label class="field"><span>有效至</span><input v-model="form.expiresOn" type="date" required data-test="sv-identity-expires"></label>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-identity-submit" :disabled="dialogBusy">记录 DEV 核验</button>
        </footer>
      </form>

      <!-- 创建 DEV 产品 -->
      <form v-else-if="dialogKind === 'create-product'" @submit.prevent="submitCreateProduct">
        <label class="field"><span>产品编码</span><input v-model="form.productCode" required maxlength="64" placeholder="SG_DEV_TEST" data-test="sv-product-code"></label>
        <label class="field"><span>产品名称</span><input v-model="form.productName" required maxlength="128" placeholder="新的 DEV 储值产品" data-test="sv-product-name"></label>
        <p class="h-caption">创建后沿用 DEV 标准政策，不能在此改变金额、利率与有效期。</p>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-create-product-submit" :disabled="dialogBusy">创建产品</button>
        </footer>
      </form>

      <!-- 产品入口开关确认 -->
      <form v-else-if="dialogKind === 'toggle-product' && toggleProduct" @submit.prevent="submitToggleProduct">
        <p>{{ toggleProduct.product.productName }}</p>
        <div class="notice">{{ toggleNotice }}。已存在交易、账户和账本保留。</div>
        <p class="h-error" role="alert">{{ dialogError }}</p>
        <footer>
          <button type="button" class="btn" @click="closeDialog">取消</button>
          <button class="btn primary" type="submit" data-test="sv-toggle-submit" :disabled="dialogBusy">确认更新</button>
        </footer>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
/* 拾光储值财务面（SHIGUANG-BATCH-05 S05）：一比一承接冻结参考 finance.js 的储值账户、充值处理、
   审批工作台、产品与政策四个工作面与对账差异（储值）页签（B5-H6 拆路由）。
   真实合同见本项 contracts.md §5-§7 与 §2.2（B5-H8…H22）；预览审阅情景/固定示例数据不迁入。 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { ElMessage } from 'element-plus'
import { formatShiguangFinanceMoney as money } from '@/utils/money'
import {
  bucketLabel,
  sourceTypeLabel
} from '@/utils/presentation'
import { storedValueAdminApi } from '@/api/storedvalue'
import { useAuthStore } from '@/stores/auth'
import type {
  StoredValueApproval,
  StoredValueFinanceAccount,
  StoredValueFinanceLedgerEntry,
  StoredValueFinanceRecharge,
  StoredValueOrderFacts,
  StoredValueProduct,
  StoredValueProvider,
  StoredValueReconciliationDifference,
  StoredValueReconciliationRun
} from '@/api/storedvalue'

type StorageView = 'accounts' | 'recharges' | 'approvals' | 'products' | 'reconciliation'
type Surface = 'loading' | 'error' | 'empty' | 'ready'
type DialogKind =
  | 'freeze' | 'unfreeze' | 'begin-close' | 'close-account' | 'order-facts'
  | 'posting-recovery' | 'recharge-refund' | 'dispute' | 'recharge-evidence' | 'recharge-result'
  | 'approval-lookup' | 'approve' | 'reject' | 'revoke'
  | 'request-adjustment' | 'run' | 'policy' | 'identity' | 'create-product' | 'toggle-product'
  | null

interface FinanceTab {
  key: string
  label: string
  permission: string
  routeName: string
  inPlace: boolean
}

/* 参考六面导航（ref/h/finance.js LABELS 顺序不变）；本面就地承接储值四面 + 储值对账，支付证据路由离开。
   对账差异（储值）的读端点权限是 stored-value:finance:view（PaymentAdminController 不适用于储值侧） */
const TABS: FinanceTab[] = [
  { key: 'payments', label: '支付证据', permission: 'payment:admin', routeName: 'SystemPayments', inPlace: false },
  { key: 'reconciliation', label: '对账差异', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: true },
  { key: 'accounts', label: '储值账户', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: true },
  { key: 'recharges', label: '充值处理', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: true },
  { key: 'approvals', label: '审批工作台', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: true },
  { key: 'products', label: '产品与政策', permission: 'stored-value:finance:view', routeName: 'SystemStoredValue', inPlace: true }
]

const pageSize = 10
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const actorName = computed(() => auth.user?.nickname || auth.user?.username || '管理员')
const canView = computed(() => auth.hasPermission('stored-value:finance:view'))
const approvalReviewPermissions = ['stored-value:recharge:review', 'stored-value:refund:review', 'stored-value:account:unfreeze:review', 'stored-value:adjustment:review']
const approvalExecutePermissions = ['stored-value:recharge:execute', 'stored-value:refund:manage', 'stored-value:account:unfreeze', 'stored-value:adjustment:execute']
const canViewApprovals = computed(() => [...approvalReviewPermissions, ...approvalExecutePermissions].some((permission) => auth.hasPermission(permission)))
const canManagePolicy = computed(() => auth.hasPermission('stored-value:policy:manage'))
const canFreezeAccount = computed(() => auth.hasPermission('stored-value:account:freeze'))
const canUnfreezeAccount = computed(() => auth.hasPermission('stored-value:account:unfreeze'))
const canCloseAccount = computed(() => auth.hasPermission('stored-value:account:close'))
const canRefundRecharge = computed(() => auth.hasPermission('stored-value:refund:manage'))
const canRecoverRecharge = computed(() => auth.hasPermission('stored-value:recharge:execute'))
const canDisputeRecharge = computed(() => auth.hasPermission('stored-value:dispute:manage'))
const canRequestAdjustment = computed(() => auth.hasPermission('stored-value:adjustment:execute'))
const canRunReconciliation = computed(() => auth.hasPermission('stored-value:reconciliation:run'))

const permissionFor = (tab: FinanceTab) =>
  tab.key === 'approvals' ? canViewApprovals.value : auth.hasPermission(tab.permission)
const visibleTabs = computed(() => TABS.filter((tab) => permissionFor(tab)))
const denied = computed(() => !visibleTabs.value.some((tab) => tab.inPlace))

const initialView = (): StorageView => (canView.value ? 'accounts' : 'approvals')
const view = ref<StorageView>(initialView())

const surface = ref<Surface>('loading')
const loadError = ref('')
const rootEl = ref<HTMLElement>()
const dialogEl = ref<HTMLDialogElement>()

const providers = ref<StoredValueProvider[]>([])
const accounts = ref<StoredValueFinanceAccount[]>([])
const accountTotal = ref(0)
const accountPage = ref(1)
const selectedAccount = ref<StoredValueFinanceAccount | null>(null)
const ledger = ref<StoredValueFinanceLedgerEntry[]>([])
const accountFacts = ref<StoredValueOrderFacts | null>(null)

const recharges = ref<StoredValueFinanceRecharge[]>([])
const rechargeTotal = ref(0)
const rechargePage = ref(1)
const selectedRecharge = ref<StoredValueFinanceRecharge | null>(null)

const approvals = ref<StoredValueApproval[]>([])
const approvalTotal = ref(0)
const approvalPage = ref(1)
const selectedApproval = ref<StoredValueApproval | null>(null)

const products = ref<StoredValueProduct[]>([])
const busyProductId = ref<number | null>(null)

const runs = ref<StoredValueReconciliationRun[]>([])
const differences = ref<StoredValueReconciliationDifference[]>([])
const differenceTotal = ref(0)
const differencePage = ref(1)
const selectedDifference = ref<StoredValueReconciliationDifference | null>(null)

const dialogKind = ref<DialogKind>(null)
const dialogBusy = ref(false)
const dialogError = ref('')
const returnFocus = ref<HTMLElement | null>(null)
const form = ref({
  businessRef: '',
  reason: '',
  orderNo: '',
  caseId: '',
  amount: '',
  approvalNo: '',
  runDate: todayText(),
  userId: '',
  digest: '',
  expiresOn: '',
  productCode: '',
  productName: ''
})
const policyProduct = ref<StoredValueProduct | null>(null)
const identityProduct = ref<StoredValueProduct | null>(null)
const toggleProduct = ref<{ product: StoredValueProduct; key: 'recharge' | 'spend' | 'status' } | null>(null)

const scopeBadge = computed(() => {
  const dev = providers.value.find((provider) => provider.enabled) || providers.value[0]
  return dev ? `${dev.providerEnv} / ${dev.providerName}` : '渠道未读取'
})

const accountRailCaption = computed(() => `全部产品 · 共 ${accountTotal.value} 个`)
const rechargeRailCaption = computed(() => `全部状态 · 共 ${rechargeTotal.value} 条`)
const storedRailCaption = computed(() => {
  const latest = runs.value[0]
  return latest ? `最近一次运行 ${latest.runRef} · 差异 ${latest.differenceCount}` : '尚未运行储值对账'
})

/** 按编号查询到的终态审批固定钉在 rail 末尾（参考「当前凭编号查看终态」） */
const pinnedApproval = computed(() => {
  const approval = selectedApproval.value
  if (!approval) return null
  return approvals.value.some((item) => item.approvalNo === approval.approvalNo) ? null : approval
})

const accountTotalBalance = computed(() => {
  const account = selectedAccount.value
  if (!account) return 0
  return Number(account.principalAvailable) + Number(account.principalFrozen)
    + Number(account.bonusAvailable) + Number(account.bonusFrozen)
})

/** B5-H15：未入账态（渠道成功但尚未入账/结果未知）实际赠送显示 0，不前端倒算 */
const postedRecharge = computed(() =>
  ['SUCCEEDED', 'REFUNDED'].includes(selectedRecharge.value?.status || ''))

/* 充值证据段文案（ref/h/finance.js rechargesView，按真实状态分支；不声称到账/扣款） */
const channelOutcomeLine = computed(() => {
  const recharge = selectedRecharge.value
  if (!recharge) return ''
  if (recharge.status === 'MANUAL_REVIEW') return '渠道结果未能确定，当前不能据此确认入账或再次发起扣款。'
  if (recharge.status === 'REFUNDING') return '退款已请求，渠道结果待确认；不能描述为退款到账。'
  if (recharge.status === 'DISPUTED') return '争议已登记，渠道处理结果待确认；冻结事实以账本为准。'
  if (recharge.status === 'REFUNDED') return '退款已完成，原路退回事实以渠道记录为准。'
  if (['PROVIDER_SUCCEEDED_PENDING_POSTING', 'POSTING'].includes(recharge.status)) return '渠道已返回成功事实，本地正在完成入账核对。'
  if (recharge.status === 'SUCCEEDED') return '成功证据已留存。'
  return '渠道结果以证据为准，尚未形成成功事实。'
})

const localPostingLine = computed(() => {
  const recharge = selectedRecharge.value
  if (!recharge) return ''
  if (recharge.status === 'MANUAL_REVIEW') return '自动恢复入口不可用，需要核对最小证据与现有人工处置路径；不自动重试扣款。'
  if (recharge.status === 'PROVIDER_SUCCEEDED_PENDING_POSTING') return '尚未完成本金和赠送金入账。恢复前需要再次核对渠道与目标快照。'
  if (recharge.status === 'POSTING') return '正在执行入账，请勿重复提交恢复申请。'
  if (recharge.status === 'REFUNDING') return '原入账记录保留，退款结果未确认前没有本地冲销结论。'
  if (recharge.status === 'REFUNDED') return '已完成原充值的全额退款处理。赠送金同时回收，账本保留。'
  if (recharge.status === 'DISPUTED') return '争议冻结已按规则登记，可用资金相应减少。'
  if (recharge.status === 'SUCCEEDED') return '已分别记入本金与赠送金账本。'
  return '尚无本地入账结论；以回调与终态证据为准。'
})

const ledgerGroups = computed(() => {
  const groups = new Map<string, {
    transactionRef: string
    title: string
    occurredAt: string
    reason: string
    sourceTypeLabel: string
    sourceRef: string
    lines: Array<{ bucketLabel: string; directionLabel: string; amount: number }>
  }>()
  for (const entry of ledger.value) {
    const key = entry.transactionRef
    let group = groups.get(key)
    if (!group) {
      group = {
        transactionRef: entry.transactionRef,
        title: ledgerTitle(entry),
        occurredAt: entry.occurredAt,
        reason: entry.reason,
        sourceTypeLabel: sourceTypeLabel(entry.sourceType),
        sourceRef: entry.sourceRef,
        lines: []
      }
      groups.set(key, group)
    }
    group.lines.push({
      bucketLabel: bucketLabel(entry.bucket),
      directionLabel: entry.direction === 'CREDIT' ? '贷' : '借',
      amount: entry.amount
    })
  }
  return [...groups.values()]
})

function ledgerTitle(entry: StoredValueFinanceLedgerEntry) {
  const type = entry.transactionType || ''
  if (type.startsWith('RECHARGE_PRINCIPAL_POSTING') || type.startsWith('RECHARGE_BONUS_POSTING')) return '充值入账'
  if (type.startsWith('RECHARGE_REFUND')) return '充值退款'
  if (type.startsWith('RECHARGE_LIMIT')) return '额度预留'
  if (type.startsWith('RECHARGE')) return '充值'
  if (type.startsWith('ORDER_PAYMENT')) return '订单支付'
  if (type.startsWith('ORDER_REFUND')) return '订单退款'
  if (type.startsWith('ACCOUNT_')) return '账户生命周期'
  if (type.startsWith('MANUAL_ADJUSTMENT')) return '人工调账'
  if (type.startsWith('DISPUTE')) return '渠道争议'
  const names: Record<string, string> = {
    RECHARGE: '充值入账',
    RECHARGE_REFUND: '充值退款',
    ORDER_PAYMENT: '订单支付',
    ORDER_REFUND: '订单退款',
    ACCOUNT_LIFECYCLE: '账户生命周期',
    MANUAL_ADJUSTMENT: '人工调账',
    DISPUTE: '渠道争议'
  }
  return names[type] || `${type} · ${entry.sourceRef}`
}

const storedChannelMinusLocal = computed(() => {
  const difference = selectedDifference.value
  if (!difference) return 0
  return Number(difference.providerAmount ?? 0) - Number(difference.localAmount ?? 0)
})

const canReviewSelected = computed(() => {
  const approval = selectedApproval.value
  return Boolean(approval) && auth.hasPermission(approval!.reviewPermission)
})
const canExecuteSelected = computed(() => {
  const approval = selectedApproval.value
  return Boolean(approval) && auth.hasPermission(approval!.executePermission)
})
/* 复核人引用不可逆（B5-H18 受控出参）：复核人同主体不能客户端判定，由服务端执行人≠复核人 DB CHECK 终审 */
const canRevokeSelected = computed(() => {
  const approval = selectedApproval.value
  if (!approval) return false
  return ['PENDING', 'APPROVED'].includes(approval.status)
    && approval.requesterIsCurrentActor
    && canExecuteSelected.value
})

const dialogTitle = computed(() => {
  const titles: Record<Exclude<DialogKind, null>, string> = {
    freeze: '冻结储值账户',
    unfreeze: '申请按原冻结记录解冻',
    'begin-close': '开始账户注销',
    'close-account': '完成账户注销',
    'order-facts': '订单原桶说明',
    'posting-recovery': '申请恢复待入账充值',
    'recharge-refund': '申请充值全额退款',
    dispute: '登记渠道争议',
    'recharge-evidence': '充值异常最小证据',
    'recharge-result': '退款结果仍待确认',
    'approval-lookup': '按编号查询审批',
    approve: '批准审批',
    reject: '拒绝审批',
    revoke: '撤回审批',
    'request-adjustment': '申请本金差异调账',
    run: '运行储值对账',
    policy: '既有政策快照',
    identity: 'DEV 身份核验',
    'create-product': '创建 DEV 储值产品',
    'toggle-product': '更新产品入口'
  }
  return dialogKind.value ? titles[dialogKind.value] : ''
})

const toggleNotice = computed(() => {
  const item = toggleProduct.value
  if (!item) return ''
  if (item.key === 'status') return (item.product.status === 'ENABLED' ? '停用' : '启用') + '产品'
  if (item.key === 'recharge') return (item.product.rechargeEnabled ? '关闭' : '开启') + '充值入口'
  return (item.product.spendEnabled ? '关闭' : '开启') + '消费入口'
})

/* 状态标签（参考 ST 映射 + 真实补充态） */
const DANGER = new Set(['FAILED', 'EXPIRED', 'MISSING_PROVIDER', 'MISSING_SYSTEM', 'MANUAL_REVIEW', 'CLOSED'])
const WARN = new Set(['PENDING', 'APPROVED', 'OPEN', 'DIFFERENCE', 'FROZEN', 'PROVIDER_SUCCEEDED_PENDING_POSTING',
  'REFUNDING', 'PENDING_REVIEW', 'UNDER_REVIEW', 'CANCELED', 'TIMEOUT', 'POSTING', 'DISPUTED', 'CLOSING', 'REJECTED', 'REVOKED'])
const tone = (status?: string) => {
  const value = status || ''
  if (DANGER.has(value)) return 'danger'
  if (WARN.has(value)) return 'warn'
  return 'success'
}
const accountStatusLabel = (status?: string) =>
  ({ NORMAL: '正常', FROZEN: '已冻结', CLOSING: '注销中', CLOSED: '已注销' }[status || ''] || status || '—')
const accountBadgeTone = tone
const RECHARGE_LABELS: Record<string, string> = {
  INITIATED: '已创建', WAITING_PROVIDER: '等待回调', PROVIDER_SUCCEEDED_PENDING_POSTING: '渠道成功 · 待入账',
  POSTING: '入账中', SUCCEEDED: '已成功', FAILED: '失败', CANCELED: '已取消', TIMED_OUT: '已超时',
  REFUNDING: '退款处理中', REFUNDED: '已退款', DISPUTED: '争议处理中', MANUAL_REVIEW: '需人工处置'
}
const rechargeStatusLabel = (status?: string) => RECHARGE_LABELS[status || ''] || status || '—'
const rechargeBadgeTone = tone
const approvalActionLabel = (action?: string) =>
  ({ RECHARGE_POSTING_RECOVERY: '待入账恢复', RECHARGE_REFUND: '充值全额退款', ACCOUNT_UNFREEZE: '账户解冻', BALANCE_ADJUSTMENT: '本金差异调账' }[action || ''] || action || '—')
const approvalStatusLabel = (status?: string) =>
  ({ PENDING: '待复核', APPROVED: '已批准 · 待执行', EXECUTING: '执行中', CONSUMED: '已执行', REJECTED: '已拒绝', REVOKED: '已撤回', EXPIRED: '已过期', FAILED: '执行失败' }[status || ''] || status || '—')
const approvalBadgeTone = tone
const storedStatusLabel = (status?: string) =>
  ({ OPEN: '待处理', UNDER_REVIEW: '复核中', RESOLVED: '已解决', MANUAL_REVIEW: '需人工处置' }[status || ''] || status || '—')
const storedBadgeTone = tone
const differenceTypeText = (type?: string) =>
  type ? type.replaceAll('_', ' ').toLowerCase() : '—'

const shortTime = (value?: string) => (value ? value.replace('T', ' ').slice(5, 16) : '—')
const shortRef = (value?: string) => (value ? value.slice(-4) : '—')

function todayText() {
  const now = new Date()
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset())
  return now.toISOString().slice(0, 10)
}

const readableError = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback

async function loadAccounts(selectFirst = true) {
  const page = await storedValueAdminApi.accounts({ pageNum: accountPage.value, pageSize })
  accounts.value = page.records
  accountTotal.value = page.total
  if (selectFirst || !accounts.value.some((item) => item.accountRef === selectedAccount.value?.accountRef)) {
    selectedAccount.value = page.records[0] || null
    await loadSelectedAccountExtras()
  }
}

async function loadSelectedAccountExtras() {
  const account = selectedAccount.value
  if (!account) {
    ledger.value = []
    accountFacts.value = null
    return
  }
  // B5-H9：按账户账本（S03 服务端 accountRef 筛选）
  const page = await storedValueAdminApi.ledger({ accountRef: account.accountRef, pageNum: 1, pageSize: 20 })
  ledger.value = page.records
}

async function loadRecharges(selectFirst = true) {
  const page = await storedValueAdminApi.recharges({ pageNum: rechargePage.value, pageSize })
  recharges.value = page.records
  rechargeTotal.value = page.total
  if (selectFirst || !recharges.value.some((item) => item.rechargeRef === selectedRecharge.value?.rechargeRef)) {
    selectedRecharge.value = page.records[0] || null
  }
}

async function loadApprovals(selectFirst = true) {
  const page = await storedValueAdminApi.approvals({ pageNum: approvalPage.value, pageSize })
  approvals.value = page.records
  approvalTotal.value = page.total
  if (selectFirst || !approvals.value.some((item) => item.approvalNo === selectedApproval.value?.approvalNo)) {
    selectedApproval.value = page.records[0] || null
  }
}

async function loadProducts() {
  products.value = await storedValueAdminApi.products()
  try {
    providers.value = await storedValueAdminApi.providers()
  } catch {
    providers.value = []
  }
}

async function loadStoredReconciliation(selectFirst = true) {
  const [runPage, differencePageResult] = await Promise.all([
    storedValueAdminApi.reconciliationRuns({ pageNum: 1, pageSize: 5 }),
    storedValueAdminApi.reconciliationDifferences({ pageNum: differencePage.value, pageSize })
  ])
  runs.value = runPage.records
  differences.value = differencePageResult.records
  differenceTotal.value = differencePageResult.total
  if (selectFirst || !differences.value.some((item) => item.differenceRef === selectedDifference.value?.differenceRef)) {
    selectedDifference.value = differencePageResult.records[0] || null
  }
}

async function bootstrap() {
  surface.value = 'loading'
  loadError.value = ''
  try {
    if (view.value === 'accounts') {
      await loadAccounts()
      if (!accounts.value.length) {
        surface.value = 'empty'
        return
      }
    } else if (view.value === 'recharges') {
      await loadRecharges()
      if (!recharges.value.length) {
        surface.value = 'empty'
        return
      }
    } else if (view.value === 'approvals') {
      await loadApprovals()
    } else if (view.value === 'products') {
      await loadProducts()
      if (!products.value.length) {
        surface.value = 'empty'
        return
      }
    } else {
      await loadStoredReconciliation()
    }
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    loadError.value = readableError(error, '财务记录加载失败，请重试。')
  }
}

function reload() {
  void bootstrap()
}

onMounted(bootstrap)

watch(() => route.name, (name) => {
  if (name === 'SystemStoredValue' && view.value === initialView()) {
    return
  }
  if (name === 'SystemStoredValue') {
    view.value = initialView()
    void bootstrap()
  }
})

function go(tab: FinanceTab) {
  if (!tab.inPlace) {
    void router.push({ name: tab.routeName })
    return
  }
  if (tab.key === view.value) return
  view.value = tab.key as StorageView
  void bootstrap()
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>(`[data-test="sv-tab-${tab.key}"]`)?.focus({ preventScroll: true }))
}

function selectAccount(account: StoredValueFinanceAccount) {
  selectedAccount.value = account
  returnFocus.value = null
  void loadSelectedAccountExtras().then(() => {
    void nextTick(() => rootEl.value?.querySelector<HTMLElement>('.h-detail h2')?.focus({ preventScroll: true }))
  })
}

function turnAccountPage(delta: number) {
  accountPage.value = Math.max(1, accountPage.value + delta)
  surface.value = 'loading'
  void loadAccounts().then(() => { surface.value = accounts.value.length ? 'ready' : 'empty' })
    .catch((error) => { surface.value = 'error'; loadError.value = readableError(error, '储值账户加载失败，请重试。') })
}

function selectRecharge(recharge: StoredValueFinanceRecharge) {
  selectedRecharge.value = recharge
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('.h-detail h2')?.focus({ preventScroll: true }))
}

function selectApproval(approval: StoredValueApproval) {
  // 列表出参不含完整申请/复核事实：选中后按编号读详情（B5-H17 同一端点）
  selectedApproval.value = approval
  void nextTick(() => rootEl.value?.querySelector<HTMLElement>('.h-detail h2')?.focus({ preventScroll: true }))
  void storedValueAdminApi.approval(approval.approvalNo)
    .then((detail) => { selectedApproval.value = detail })
    .catch(() => { /* 保留列表数据展示，不伪造事实 */ })
}

function turnRechargePage(delta: number) {
  rechargePage.value = Math.max(1, rechargePage.value + delta)
  surface.value = 'loading'
  void loadRecharges().then(() => { surface.value = recharges.value.length ? 'ready' : 'empty' })
    .catch((error) => { surface.value = 'error'; loadError.value = readableError(error, '充值记录加载失败，请重试。') })
}

function turnApprovalPage(delta: number) {
  approvalPage.value = Math.max(1, approvalPage.value + delta)
  surface.value = 'loading'
  void loadApprovals().then(() => { surface.value = 'ready' })
    .catch((error) => { surface.value = 'error'; loadError.value = readableError(error, '审批队列加载失败，请重试。') })
}

function turnDifferencePage(delta: number) {
  differencePage.value = Math.max(1, differencePage.value + delta)
  surface.value = 'loading'
  void loadStoredReconciliation().then(() => { surface.value = 'ready' })
    .catch((error) => { surface.value = 'error'; loadError.value = readableError(error, '储值对账加载失败，请重试。') })
}

/** 参考的「查看账户分账」跳转：切到账户面并尽量按充值引用定位同主体账户 */
function gotoAccountOfRecharge() {
  const recharge = selectedRecharge.value
  if (!recharge) return
  view.value = 'accounts'
  surface.value = 'loading'
  void loadAccounts().then(() => {
    const match = accounts.value.find((item) => item.userRef === recharge.userRef)
    if (match) selectedAccount.value = match
    surface.value = 'ready'
  }).catch((error) => {
    surface.value = 'error'
    loadError.value = readableError(error, '储值账户加载失败，请重试。')
  })
}

function gotoAccountByRef(accountRef: string) {
  view.value = 'accounts'
  surface.value = 'loading'
  void loadAccounts().then(() => {
    const match = accounts.value.find((item) => item.accountRef === accountRef)
    if (match) selectedAccount.value = match
    surface.value = 'ready'
  }).catch((error) => {
    surface.value = 'error'
    loadError.value = readableError(error, '储值账户加载失败，请重试。')
  })
}

/** 参考的「回到目标重新核对」：按审批类型跳到对应工作面 */
function gotoTargetOfApproval() {
  const approval = selectedApproval.value
  if (!approval) return
  if (approval.targetType === 'RECONCILIATION_DIFFERENCE') {
    view.value = 'reconciliation'
    void bootstrap()
    return
  }
  if (approval.targetType === 'RECHARGE') {
    view.value = 'recharges'
    void bootstrap()
    return
  }
  gotoAccountByRef(approval.targetRef)
}

/* ---- 弹层 ---- */
function openDialog(kind: Exclude<DialogKind, null>) {
  dialogKind.value = kind
  dialogError.value = ''
  dialogBusy.value = false
  form.value.reason = ''
  form.value.businessRef = ''
  returnFocus.value = document.activeElement as HTMLElement | null
  if (kind === 'run') form.value.runDate = todayText()
  if (kind === 'identity') {
    form.value.userId = ''
    form.value.digest = `dev-evidence-${Date.now().toString(36)}`
    form.value.expiresOn = todayText().slice(0, 4) + '-12-31'
  }
  if (kind === 'request-adjustment' && selectedDifference.value) {
    form.value.amount = storedChannelMinusLocal.value.toFixed(2)
  }
  void nextTick(() => dialogEl.value?.showModal())
}

function openPolicyDialog(product: StoredValueProduct) {
  policyProduct.value = product
  openDialog('policy')
}

function openIdentityDialog(product: StoredValueProduct) {
  identityProduct.value = product
  openDialog('identity')
}

function openToggleDialog(product: StoredValueProduct, key: 'recharge' | 'spend' | 'status') {
  toggleProduct.value = { product, key }
  openDialog('toggle-product')
}

function closeDialog() {
  dialogEl.value?.close()
  dialogKind.value = null
}

function focusBack() {
  const target = returnFocus.value || rootEl.value?.querySelector<HTMLElement>('[aria-pressed="true"]')
  target?.focus({ preventScroll: true })
  returnFocus.value = null
}

async function runCooled(task: () => Promise<void>) {
  if (dialogBusy.value) return
  dialogBusy.value = true
  dialogError.value = ''
  try {
    await task()
  } catch (error) {
    dialogError.value = readableError(error, '操作失败，请稍后重试。')
  } finally {
    dialogBusy.value = false
  }
}

async function refreshAfterWrite() {
  closeDialog()
  surface.value = 'ready'
  if (view.value === 'accounts') await loadAccounts(false)
  if (view.value === 'recharges') await loadRecharges(false)
  if (view.value === 'approvals') await loadApprovals(false)
  if (view.value === 'products') await loadProducts()
  if (view.value === 'reconciliation') await loadStoredReconciliation(false)
}

function submitAccountAction(action: 'freeze' | 'unfreeze' | 'begin-close' | 'close-account') {
  const account = selectedAccount.value
  if (!account || dialogBusy.value) return
  if (!form.value.reason.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  void runCooled(async () => {
    if (action === 'unfreeze') {
      await storedValueAdminApi.unfreeze(account.accountRef, { reason: form.value.reason.trim() })
      ElMessage.success('解冻审批已提交，等待另一主体复核。资金未发生变化。')
    } else {
      const payload = { reason: form.value.reason.trim(), businessRef: form.value.businessRef.trim() }
      if (action === 'freeze') {
        await storedValueAdminApi.freeze(account.accountRef, payload)
        ElMessage.success('账户已冻结，本金与赠送金分别转桶。')
      }
      if (action === 'begin-close') {
        await storedValueAdminApi.beginClosing(account.accountRef, payload)
        ElMessage.success('账户进入注销中。')
      }
      if (action === 'close-account') {
        await storedValueAdminApi.close(account.accountRef, payload)
        ElMessage.success('账户已注销。历史账本保留。')
      }
    }
    await refreshAfterWrite()
  })
}

function submitOrderFacts() {
  const orderNo = form.value.orderNo.trim()
  if (!orderNo || dialogBusy.value) return
  void runCooled(async () => {
    accountFacts.value = await storedValueAdminApi.orderStoredFacts(orderNo)
    closeDialog()
    ElMessage.success('原桶说明已读取。')
  })
}

function submitRechargeApproval(kind: 'posting-recovery' | 'recharge-refund') {
  const recharge = selectedRecharge.value
  if (!recharge || dialogBusy.value) return
  if (!form.value.reason.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  void runCooled(async () => {
    if (kind === 'posting-recovery') {
      await storedValueAdminApi.recoverPosting(recharge.rechargeRef, form.value.reason.trim())
      ElMessage.success('待入账恢复审批已提交，等待另一主体复核。')
    } else {
      await storedValueAdminApi.refundRecharge(recharge.rechargeRef, form.value.reason.trim())
      ElMessage.success('充值退款审批已提交，等待另一主体复核。')
    }
    await refreshAfterWrite()
  })
}

function submitDispute() {
  const recharge = selectedRecharge.value
  if (!recharge || dialogBusy.value) return
  const amount = Number(form.value.amount)
  if (!form.value.caseId.trim()) {
    dialogError.value = '渠道争议编号不能为空。'
    return
  }
  if (!Number.isFinite(amount) || amount <= 0) {
    dialogError.value = '争议金额必须是正数。'
    return
  }
  if (!form.value.reason.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  void runCooled(async () => {
    await storedValueAdminApi.disputeRecharge(recharge.rechargeRef, form.value.caseId.trim(), amount, form.value.reason.trim())
    ElMessage.success('充值争议已登记。')
    await refreshAfterWrite()
  })
}

function submitApprovalLookup() {
  const approvalNo = form.value.approvalNo.trim()
  if (!approvalNo || dialogBusy.value) return
  void runCooled(async () => {
    const approval = await storedValueAdminApi.approval(approvalNo)
    selectedApproval.value = approval
    closeDialog()
    ElMessage.success(`已读取审批 ${approval.approvalNo}（${approvalStatusLabel(approval.status)}）。`)
  })
}

function submitApprovalDecision(kind: DialogKind) {
  const approval = selectedApproval.value
  if (!approval || dialogBusy.value) return
  if (kind !== 'approve' && kind !== 'reject' && kind !== 'revoke') return
  if (!form.value.reason.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  void runCooled(async () => {
    if (kind === 'approve') {
      selectedApproval.value = await storedValueAdminApi.approveApproval(approval.approvalNo, form.value.reason.trim())
      ElMessage.success('审批已批准，等待具备动作权限的主体执行。')
    } else if (kind === 'reject') {
      selectedApproval.value = await storedValueAdminApi.rejectApproval(approval.approvalNo, form.value.reason.trim())
      ElMessage.success('审批已拒绝。')
    } else {
      selectedApproval.value = await storedValueAdminApi.revokeApproval(approval.approvalNo, form.value.reason.trim())
      ElMessage.success('审批已撤回。')
    }
    await refreshAfterWrite()
  })
}

async function executeSelected() {
  const approval = selectedApproval.value
  if (!approval || dialogBusy.value || !canExecuteSelected.value) return
  dialogBusy.value = true
  dialogError.value = ''
  try {
    selectedApproval.value = await storedValueAdminApi.executeApproval(approval.approvalNo)
    ElMessage.success('审批已执行。执行结果以记录为准，不代表资金已到账。')
    await refreshAfterWrite()
  } catch (error) {
    ElMessage.error(readableError(error, '审批执行失败'))
  } finally {
    dialogBusy.value = false
  }
}

function submitRequestAdjustment() {
  const difference = selectedDifference.value
  if (!difference || dialogBusy.value) return
  const amount = Number(form.value.amount)
  const expected = storedChannelMinusLocal.value
  if (amount === 0 || Math.abs(amount - expected) > 0.001) {
    dialogError.value = '申请金额必须等于渠道减本地的非零差额。'
    return
  }
  if (!form.value.reason.trim()) {
    dialogError.value = '处理原因不能为空。'
    return
  }
  void runCooled(async () => {
    await storedValueAdminApi.requestAdjustment(difference.differenceRef, amount, form.value.reason.trim())
    ElMessage.success('申请已记录，等待另一主体复核。资金未发生变化。')
    await refreshAfterWrite()
  })
}

function submitRunReconciliation() {
  if (dialogBusy.value) return
  void runCooled(async () => {
    const run = await storedValueAdminApi.runReconciliation(form.value.runDate)
    ElMessage.success(`储值对账完成：${run.runRef}，差异 ${run.differenceCount} 条。`)
    await refreshAfterWrite()
  })
}

function submitIdentity() {
  const product = identityProduct.value
  if (!product || dialogBusy.value) return
  const userId = Number(form.value.userId)
  if (!Number.isInteger(userId) || userId <= 0) {
    dialogError.value = '请输入有效的用户 ID。'
    return
  }
  if (!form.value.digest.trim()) {
    dialogError.value = '核验证据摘要不能为空。'
    return
  }
  void runCooled(async () => {
    await storedValueAdminApi.verifyIdentity(userId, form.value.digest.trim(), form.value.expiresOn)
    ElMessage.success(`DEV 核验已记录：用户 ${userId}，有效至 ${form.value.expiresOn}。`)
    closeDialog()
  })
}

function submitCreateProduct() {
  if (dialogBusy.value) return
  if (!form.value.productCode.trim() || !form.value.productName.trim()) {
    dialogError.value = '产品编码和名称不能为空。'
    return
  }
  void runCooled(async () => {
    const product = await storedValueAdminApi.createProduct({
      productCode: form.value.productCode.trim(),
      productName: form.value.productName.trim()
    })
    products.value.push(product)
    ElMessage.success('DEV 储值产品已创建：沿用现有标准政策。')
    closeDialog()
  })
}

function submitToggleProduct() {
  const item = toggleProduct.value
  if (!item || dialogBusy.value) return
  void runCooled(async () => {
    const payload = item.key === 'status'
      ? { status: item.product.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' }
      : item.key === 'recharge'
        ? { rechargeEnabled: !item.product.rechargeEnabled }
        : { spendEnabled: !item.product.spendEnabled }
    const updated = await storedValueAdminApi.setProductFlags(item.product.id, payload)
    products.value = products.value.map((entry) => entry.id === updated.id ? updated : entry)
    ElMessage.success('产品入口已更新。')
    closeDialog()
  })
}
</script>

<style scoped>
/* 实现侧必要件（IMPL_ONLY）：rail 服务端分页，参考是固定示例列表没有分页 */
.fin-rail-pager {
  display: flex;
  gap: 8px;
  padding: 12px 0;
}

.h-ledger-section {
  margin-top: 24px;
}

.h-ledger-section h3 {
  margin-bottom: 4px;
}

/* 实现侧必要件（IMPL_ONLY）：真实引用 25+ 字符（参考是短号夹具），换行纪律与 .h-pick strong 一致 */
.h-detail-head > div,
.h-detail-head h2 {
  min-width: 0;
}

.h-detail-head h2 {
  overflow-wrap: anywhere;
}
</style>
