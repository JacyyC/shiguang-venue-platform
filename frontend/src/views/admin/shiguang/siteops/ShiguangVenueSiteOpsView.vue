<template>
  <div ref="rootEl" class="sga-siteops" data-test="sga-siteops-root">
    <header class="f-heading">
      <div>
        <p class="eyebrow">场馆运营 / 现场与资产</p>
        <h1>把现场的事，交代清楚</h1>
        <p>沿着当班任务推进，把未尽事项交到下一班，也把设备的每次变化留下来。</p>
      </div>
      <div class="date">
        <strong>{{ todayLabel }}</strong>当班工作
      </div>
    </header>

    <nav class="f-tabs" aria-label="现场工作面">
      <button
        v-for="item in TABS"
        :key="item.key"
        type="button"
        :data-test="`tab-${item.key}`"
        :aria-current="tab === item.key"
        @click="switchTab(item.key)"
      >
        {{ item.label }}
      </button>
    </nav>

    <section v-if="surface === 'loading'" class="f-state" role="status" aria-busy="true" data-test="surface-loading">
      <h2>正在读取当前场馆</h2>
      <p>记录还没有返回，暂时不能开始处理。</p>
      <button class="btn" type="button" data-test="surface-reload" @click="reload">重新读取</button>
    </section>

    <section v-else-if="surface === 'error'" class="f-state" role="alert" data-test="surface-error">
      <h2>暂时无法读取记录</h2>
      <p>读取失败不代表没有记录。请重新读取当前范围。</p>
      <button class="btn primary" type="button" data-test="surface-reload" @click="reload">重新读取</button>
    </section>

    <section v-else-if="surface === 'lock'" class="f-state" role="status" data-test="surface-lock">
      <h2>尚未绑定可管理场馆</h2>
      <p>请由系统管理员在用户管理中绑定场馆，再重新进入工作空间。</p>
      <button class="btn" type="button" data-test="surface-recheck" @click="reload">重新检查</button>
    </section>

    <template v-else>
      <section v-if="tab === 'shift'" data-test="pane-shift">
        <div v-if="!canSite" class="f-state" role="status" data-test="denied-shift">
          <h2>当前没有此项权限</h2>
          <p>此方向的读取与写入都需要相应授权。可返回其他已授权任务。</p>
        </div>
        <template v-else>
          <section class="f-shift-band" data-test="shift-band">
            <template v-if="currentShift">
              <div>
                <p>当前班次 · {{ assigneeText(currentShift.assignedUserId) }}</p>
                <h2>{{ currentShift.shiftName }}</h2>
                <span class="badge">{{ shiftStatusLabel(currentShift.status) }}</span>
              </div>
              <div>
                <div class="f-shift-time">{{ shiftTimeRange(currentShift) }}</div>
                <p>签到 {{ clockInText(currentShift) }}{{ clockOutText(currentShift) }}</p>
              </div>
              <div class="row">
                <button
                  v-if="shiftAction(currentShift) === 'clock-in'"
                  class="btn primary"
                  type="button"
                  data-test="clock-in"
                  @click="openClockIn(currentShift, $event)"
                >
                  班次签到
                </button>
                <button
                  v-else-if="shiftAction(currentShift) === 'clock-out'"
                  class="btn"
                  type="button"
                  data-test="clock-out"
                  @click="tryClockOut($event)"
                >
                  班次签退
                </button>
                <span v-else class="muted">本班已结束</span>
                <button class="btn" type="button" data-test="edit-shift" @click="openShiftForm(true, $event)">编辑班次</button>
                <button class="btn" type="button" data-test="new-shift" @click="openShiftForm(false, $event)">新增班次</button>
              </div>
            </template>
            <div v-else class="f-state" role="status" data-test="shift-empty">
              <h2>当前范围没有记录</h2>
              <p>这是一个空范围，可以调整场馆或记录范围。</p>
              <button class="btn primary" type="button" data-test="new-shift-empty" @click="openShiftForm(false, $event)">新增班次</button>
            </div>
          </section>

          <div v-if="currentShift" class="f-shift-grid">
            <section>
              <div class="row" style="justify-content: space-between">
                <div>
                  <h2 style="margin-bottom: 4px">当班任务</h2>
                  <p class="muted">{{ openTaskCount }} 项未完成 · 按当班处理顺序阅读</p>
                </div>
                <button class="btn" type="button" data-test="new-task" @click="openTaskForm($event)">记录任务</button>
              </div>
              <article v-for="(task, index) in shiftTasks" :key="task.id" class="f-task" :data-test="`task-${task.id}`">
                <header>
                  <span class="serial">{{ String(index + 1).padStart(2, '0') }}</span>
                  <div>
                    <span class="f-time">{{ taskTimeLine(task) }}</span>
                    <h3>{{ task.title }}</h3>
                    <p>{{ taskNote(task) || '尚未填写处理结果。' }}</p>
                    <div class="row">
                      <span class="badge" :class="{ warn: task.status === 'EXCEPTION' }">{{ taskStatusLabel(task.status) }}</span>
                      <button class="btn" type="button" :data-test="`open-task-${task.id}`" @click="openTaskDetail(task, $event)">
                        查看与处理
                      </button>
                      <button
                        v-if="task.assetId && canAsset"
                        class="btn"
                        type="button"
                        :data-test="`task-asset-${task.id}`"
                        @click="jumpToAsset(task.assetId as number)"
                      >
                        设备档案
                      </button>
                    </div>
                  </div>
                </header>
              </article>
              <p v-if="!shiftTasks.length" class="muted" data-test="task-empty">本班还没有任务记录。</p>
            </section>

            <aside class="f-handover" data-test="handover-panel">
              <p class="eyebrow">本班 → 下一班</p>
              <h3>未尽事项与交接</h3>
              <p>
                接收班次：{{ receiverShiftText }}<br />
                接收人：{{ receiverUserText }}
              </p>
              <div class="f-lines">
                <div><span>未完成任务</span><strong>{{ openTaskCount }} 项</strong></div>
                <div><span>未解决异常</span><strong>{{ openIncidentCount }} 项</strong></div>
              </div>
              <span class="badge" data-test="handover-status">{{ handoverStatusText }}</span>
              <p v-if="handoverNote" style="margin-top: 14px">{{ handoverNote }}</p>
              <button v-if="!currentHandover" class="btn primary" type="button" data-test="handover-new" @click="openHandoverForm($event)">
                填写交接记录
              </button>
              <button
                v-else-if="currentHandover.status === 'SUBMITTED'"
                class="btn primary"
                type="button"
                data-test="handover-accept"
                @click="openHandoverAccept($event)"
              >
                接收交接
              </button>
              <p v-else style="margin-top: 16px">已接收交接，可在核对班次状态后签退。</p>
              <p class="f-note" style="margin: 20px 0 0">
                仍有开放任务时，必须先完成交接接收才能签退。交接记录保留事项，不自动替换任务归属。
              </p>
            </aside>
          </div>
        </template>
      </section>

      <section v-else-if="tab === 'incidents'" data-test="pane-incidents">
        <div v-if="!canSite" class="f-state" role="status" data-test="denied-incidents">
          <h2>当前没有此项权限</h2>
          <p>此方向的读取与写入都需要相应授权。可返回其他已授权任务。</p>
        </div>
        <template v-else>
          <div class="f-asset-top">
            <div>
              <h2>现场异常记录</h2>
              <p class="muted">从现场观察到处理结果，保留事件本身的上下文。</p>
            </div>
            <button class="btn primary" type="button" data-test="new-incident" @click="openIncidentForm($event)">记录现场异常</button>
          </div>
          <article v-for="incident in incidentList" :key="incident.id" class="f-incident" :data-test="`incident-${incident.id}`">
            <div>
              <span class="f-time">{{ eventLine(incident.occurredAt, '') || '未记录时间' }}</span>
              <p><span class="badge">{{ incidentStatusLabel(incident.status) }}</span></p>
            </div>
            <div>
              <h3>{{ incident.description }}</h3>
              <p>{{ incidentTypeLabel(incident.incidentType) }}</p>
              <p class="muted">
                严重程度：{{ severityLabel(incident.severity) }} · {{ incident.incidentNo
                }}<span v-if="incidentResolution(incident)"> · 结果：{{ incidentResolution(incident) }}</span>
              </p>
            </div>
            <button class="btn" type="button" :data-test="`open-incident-${incident.id}`" @click="openIncidentDetail(incident, $event)">
              {{ isOpenIncident(incident) ? '记录处理结果' : '查看详情' }}
            </button>
          </article>
          <section v-if="!incidentList.length" class="f-state" role="status" data-test="incident-empty">
            <h2>本班尚无异常记录</h2>
            <p>出现需跟进的现场情况时，可记录时间、现象和处置情况。</p>
            <button class="btn" type="button" data-test="new-incident-empty" @click="openIncidentForm($event)">记录第一条异常</button>
          </section>
          <p class="f-note" style="margin-top: 25px">解决现场异常不会自动关闭维修单，也不会自动释放场地库存。</p>
        </template>
      </section>

      <section v-else data-test="pane-assets">
        <div v-if="!canAsset" class="f-state" role="status" data-test="denied-assets">
          <h2>当前没有此项权限</h2>
          <p>此方向的读取与写入都需要相应授权。可返回其他已授权任务。</p>
        </div>
        <template v-else>
          <div class="f-asset-top">
            <div>
              <h2>设备档案</h2>
              <p class="muted">从设备出发，查看巡检、维修与状态变化。</p>
            </div>
            <button class="btn" type="button" data-test="new-asset" @click="openAssetForm(false, $event)">设备建档</button>
          </div>
          <div class="f-asset-dossier">
            <aside class="f-asset-menu" aria-label="资产台账">
              <button
                v-for="asset in assetList"
                :key="asset.id"
                class="f-pick"
                type="button"
                :aria-pressed="asset.id === selectedAssetId"
                :data-test="`pick-asset-${asset.id}`"
                @click="selectAsset(asset.id)"
              >
                <span class="f-time">{{ asset.assetNo }}</span>
                <strong>{{ asset.name }}</strong>
                <small>{{ spaceText(asset.spaceId) }} · {{ assetStatusLabel(asset.status) }}</small>
              </button>
              <p v-if="!assetList.length" class="muted" data-test="asset-empty">当前场馆还没有登记的设备资产。</p>
            </aside>
            <article v-if="selectedAsset" class="f-detail" data-test="asset-detail">
              <p class="f-asset-caption">{{ selectedAsset.assetNo }}</p>
              <h2>{{ selectedAsset.name }}</h2>
              <div class="f-status-row">
                <span class="badge">{{ assetStatusLabel(selectedAsset.status) }}</span>
                <span class="muted">{{ spaceText(selectedAsset.spaceId) }} · {{ selectedAsset.assetType }}</span>
              </div>
              <dl class="f-facts">
                <div><dt>责任人</dt><dd>{{ responsibleText(selectedAsset) }}</dd></div>
                <div><dt>当前关联库存块</dt><dd>{{ blockLinkText(selectedAsset) }}</dd></div>
                <div><dt>最近巡检</dt><dd>{{ canMaintain ? inspectionText : '维护权限未授权' }}</dd></div>
                <div><dt>维修单</dt><dd>{{ canMaintain ? repairText : '维护权限未授权' }}</dd></div>
              </dl>
              <div class="f-actions">
                <template v-if="assetMutable">
                  <button class="btn" type="button" data-test="asset-edit" @click="openAssetForm(true, $event)">编辑档案</button>
                  <button class="btn" type="button" data-test="asset-status" @click="openAssetStatus($event)">变更资产状态</button>
                </template>
                <p v-else class="f-note">已报废资产不能重新启用，状态不再改写。</p>
                <button v-if="canMaintain && assetMutable" class="btn" type="button" data-test="inspection" @click="openInspection($event)">
                  记录巡检
                </button>
                <button
                  v-if="canMaintain && assetMutable && !openRepair"
                  class="btn primary"
                  type="button"
                  data-test="repair-request"
                  @click="openRepairForm($event)"
                >
                  发起维修
                </button>
              </div>
              <div v-if="!canMaintain" class="f-permission" data-test="maintenance-denied">
                当前有资产台账权限，没有巡检维修权限。维护记录与动作不可进入。
              </div>
              <section v-else-if="openRepair" class="f-asset-record" data-test="repair-record">
                <h3>维修记录 · {{ repairStatusLabel(openRepair.status) }}</h3>
                <p>{{ openRepair.failureDescription }}</p>
                <div class="f-actions">
                  <button
                    v-if="repairActions(openRepair.status).start"
                    class="btn primary"
                    type="button"
                    data-test="repair-start"
                    @click="openRepairAction('repair-start', $event)"
                  >
                    开始维修
                  </button>
                  <button
                    v-if="repairActions(openRepair.status).complete"
                    class="btn primary"
                    type="button"
                    data-test="repair-complete"
                    @click="openRepairAction('repair-complete', $event)"
                  >
                    完成维修
                  </button>
                  <button
                    v-if="repairActions(openRepair.status).cancel"
                    class="btn"
                    type="button"
                    data-test="repair-cancel"
                    @click="openRepairAction('repair-cancel', $event)"
                  >
                    取消维修
                  </button>
                </div>
                <p class="f-note">取消或完成维修仅按明确选择处理本维修关联块，不代表场地整体恢复可售。</p>
              </section>
              <section v-else-if="lastRepair" class="f-asset-record" data-test="repair-record">
                <h3>维修记录 · {{ repairStatusLabel(lastRepair.status) }}</h3>
                <p>{{ lastRepair.resolution || '本次维修已结束' }}{{ lastRepair.actualCostAmount ? ` · 实际费用 ${money(Number(lastRepair.actualCostAmount))}` : '' }}</p>
                <p class="f-note">取消或完成维修仅按明确选择处理本维修关联块，不代表场地整体恢复可售。</p>
              </section>
              <section style="margin-top: 28px">
                <h3>生命周期记录</h3>
                <ol class="f-events" data-test="lifecycle">
                  <li v-for="event in assetEvents" :key="event.id">
                    {{ eventLine(event.occurredAt || event.createTime, lifecycleLine(event)) }}
                  </li>
                </ol>
                <p v-if="!assetEvents.length" class="muted" data-test="lifecycle-empty">这台设备还没有生命周期记录。</p>
              </section>
              <RouterLink class="f-link" to="/venue-admin/calendar">核对资源与库存安排 →</RouterLink>
            </article>
          </div>
        </template>
      </section>
    </template>

    <dialog ref="dialogEl" class="f-modal" aria-labelledby="f-modal-title" @close="onDialogClose" @keydown="onDialogKeydown">
      <h2 id="f-modal-title">{{ dialog?.title }}</h2>
      <form @submit.prevent="confirmDialog">
        <template v-if="dialog?.kind === 'clock-in' || dialog?.kind === 'clock-out' || dialog?.kind === 'clock-blocked'">
          <p>{{ dialog.targetText }}</p>
          <p v-if="dialog.kind === 'clock-out'" class="f-note">确认本班记录与未尽事项已交代清楚。</p>
        </template>

        <template v-else-if="dialog?.kind === 'shift-form'">
          <label>班次名称<input v-model="form.shiftName" name="shiftName" maxlength="128" required /></label>
          <div class="mini-grid">
            <label>岗位
              <select v-model="form.roleCode" name="roleCode">
                <option v-for="(label, code) in ROLE_CODE_LABELS" :key="code" :value="code">{{ label }}</option>
              </select>
            </label>
            <label>指派人员 ID<input v-model.number="form.assignedUserId" name="assignedUserId" type="number" min="1" required /></label>
          </div>
          <div class="mini-grid">
            <label>计划开始<input v-model="form.startAt" name="startAt" type="datetime-local" required /></label>
            <label>计划结束<input v-model="form.endAt" name="endAt" type="datetime-local" required /></label>
          </div>
          <p class="f-note">指派人必须启用且属于当前场馆；同一人员的班次不得重叠。</p>
        </template>

        <template v-else-if="dialog?.kind === 'task-form'">
          <label>任务标题<input v-model="form.taskTitle" name="title" maxlength="120" placeholder="写清需要完成的现场动作" required /></label>
          <div class="mini-grid">
            <label>任务类型
              <select v-model="form.taskType" name="taskType">
                <option v-for="(label, code) in TASK_TYPE_LABELS" :key="code" :value="code">{{ label }}</option>
              </select>
            </label>
            <label>优先级
              <select v-model="form.priority" name="priority">
                <option v-for="(label, code) in PRIORITY_LABELS" :key="code" :value="code">{{ label }}</option>
              </select>
            </label>
          </div>
          <label>计划完成时间<input v-model="form.dueAt" name="dueAt" type="datetime-local" /></label>
          <label>关联设备 ID（选填）<input v-model.number="form.taskAssetId" name="assetId" type="number" min="1" /></label>
        </template>

        <template v-else-if="dialog?.kind === 'task-detail'">
          <h3>{{ dialog.targetText }}</h3>
          <p>{{ dialog.bodyText }}</p>
          <span class="badge">{{ dialog.badgeText }}</span>
          <template v-if="dialog.taskOpen">
            <div class="f-actions">
              <button v-if="dialog.canStart" class="btn primary" type="button" data-test="task-start" @click.prevent="startTask">
                开始任务
              </button>
              <button class="btn" type="button" data-test="task-edit" @click.prevent="editTask">编辑任务</button>
            </div>
            <label>完成结果
              <select v-model="form.result" name="result">
                <option value="DONE">正常完成</option>
                <option value="EXCEPTION">异常完成</option>
                <option value="CANCELED">取消任务</option>
              </select>
            </label>
            <label>完成说明或取消原因<textarea v-model="form.note" name="reason" rows="3" maxlength="500" required></textarea></label>
          </template>
          <p v-else class="f-note">本任务已进入终态，不再开始或改写核心归属。</p>
        </template>

        <template v-else-if="dialog?.kind === 'handover-form'">
          <p>交班：{{ dialog.targetText }} → 接班：{{ handoverTargetText }}</p>
          <p class="muted">未完成任务 {{ openTaskCount }} 项；未解决异常 {{ openIncidentCount }} 项。</p>
          <label>接收班次
            <select v-model.number="form.toShiftId" name="toShift">
              <option :value="0">请选择本班之外的班次</option>
              <option v-for="item in handoverTargetShifts" :key="item.id" :value="item.id">
                {{ item.shiftName }} {{ shiftTimeRange(item) }} · {{ assigneeText(item.assignedUserId) }}
              </option>
            </select>
          </label>
          <label>异常摘要<textarea v-model="form.incidentSummary" name="incident" rows="2" maxlength="500" placeholder="当前异常及处理进度"></textarea></label>
          <label>资产问题摘要<textarea v-model="form.assetIssueSummary" name="asset" rows="2" maxlength="500" placeholder="设备问题与维修安排"></textarea></label>
          <label>处理说明<textarea v-model="form.note" name="reason" rows="3" maxlength="500" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'handover-accept'">
          <p>请核对源班次与接收班次，确认未尽事项已有接续安排。</p>
          <p>{{ dialog.targetText }}</p>
        </template>

        <template v-else-if="dialog?.kind === 'incident-form'">
          <label>异常描述<textarea v-model="form.description" name="title" rows="3" maxlength="1000" required></textarea></label>
          <div class="mini-grid">
            <label>发生时间<input v-model="form.occurredAt" name="time" type="datetime-local" required /></label>
            <label>严重程度
              <select v-model="form.severity" name="severity">
                <option v-for="(label, code) in SEVERITY_LABELS" :key="code" :value="code">{{ label }}</option>
              </select>
            </label>
          </div>
          <div class="mini-grid">
            <label>异常类型
              <select v-model="form.incidentType" name="incidentType">
                <option v-for="(label, code) in INCIDENT_TYPE_LABELS" :key="code" :value="code">{{ label }}</option>
              </select>
            </label>
            <label>关联设备 ID（选填）<input v-model.number="form.incidentAssetId" name="assetId" type="number" min="1" /></label>
          </div>
          <label>已采取的处置措施<textarea v-model="form.note" name="reason" rows="3" maxlength="500" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'incident-resolve'">
          <p>{{ dialog.targetText }}</p>
          <p class="f-note">此结果只更新异常记录，相关设备与库存需分别处理。</p>
          <label>处理结果<textarea v-model="form.note" name="reason" rows="3" maxlength="1000" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'incident-detail'">
          <h3>{{ dialog.targetText }}</h3>
          <p>{{ dialog.bodyText }}</p>
          <span class="badge">{{ dialog.badgeText }}</span>
        </template>

        <template v-else-if="dialog?.kind === 'asset-form'">
          <label>资产名称<input v-model="form.assetName" name="name" maxlength="160" required /></label>
          <label>设备类型
            <select v-model="form.assetType" name="type">
              <option v-for="option in typeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label>设备序列号（选填）<input v-model="form.serialNo" name="serial" maxlength="128" /></label>
          <p class="f-note">资产编号在建档时自动生成。</p>
          <label>绑定场地
            <select v-model.number="form.spaceId" name="space">
              <option :value="0">不绑定场地</option>
              <option v-for="space in spaceList" :key="space.id" :value="space.id">{{ space.name }}</option>
            </select>
          </label>
        </template>

        <template v-else-if="dialog?.kind === 'asset-status'">
          <p>{{ dialog.targetText }}</p>
          <label>目标状态
            <select v-model="form.toStatus" name="status">
              <option v-for="code in ASSET_TARGET_STATUSES" :key="code" :value="code">{{ assetStatusLabel(code) }}</option>
            </select>
          </label>
          <label class="check"><input v-model="form.linkBlock" type="checkbox" name="block" />同时为绑定场地创建关联不可售块</label>
          <div class="mini-grid">
            <label>不可售开始<input v-model="form.blockStart" name="blockStart" type="datetime-local" /></label>
            <label>不可售结束<input v-model="form.blockEnd" name="blockEnd" type="datetime-local" /></label>
          </div>
          <p class="f-note">不勾选时仅记录设备变化。勾选后只影响这个明确时间段。</p>
          <label class="check"><input v-model="form.releaseBlock" type="checkbox" name="release" />恢复使用时释放资产当前关联库存块</label>
          <label>处理说明<textarea v-model="form.note" name="reason" rows="3" maxlength="500" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'inspection'">
          <label>巡检结果
            <select v-model="form.inspectionResult" name="result">
              <option v-for="(label, code) in INSPECTION_RESULT_LABELS" :key="code" :value="code">{{ label }}</option>
            </select>
          </label>
          <label>风险等级
            <select v-model="form.riskLevel" name="risk">
              <option v-for="(label, code) in RISK_LEVEL_LABELS" :key="code" :value="code">{{ label }}</option>
            </select>
          </label>
          <label>巡检说明<textarea v-model="form.note" name="reason" rows="3" maxlength="500" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'repair-form'">
          <p>{{ dialog.targetText }}</p>
          <label>预计费用<input v-model.number="form.cost" name="cost" type="number" min="0" step="0.01" /></label>
          <label class="check"><input v-model="form.linkBlock" type="checkbox" name="block" />同时为绑定场地创建关联不可售块</label>
          <div class="mini-grid">
            <label>不可售开始<input v-model="form.blockStart" name="blockStart" type="datetime-local" /></label>
            <label>不可售结束<input v-model="form.blockEnd" name="blockEnd" type="datetime-local" /></label>
          </div>
          <p class="f-note">不勾选时仅记录设备变化。勾选后只影响这个明确时间段。</p>
          <label>故障描述<textarea v-model="form.note" name="reason" rows="3" maxlength="1000" required></textarea></label>
        </template>

        <template v-else-if="dialog?.kind === 'repair-start'">
          <p>{{ dialog.targetText }} · 请确认接续维修安排。</p>
        </template>

        <template v-else-if="dialog?.kind === 'repair-complete' || dialog?.kind === 'repair-cancel'">
          <p>{{ dialog.targetText }}</p>
          <label v-if="dialog.kind === 'repair-complete'">实际费用<input v-model.number="form.actualCost" name="cost" type="number" min="0" step="0.01" /></label>
          <label class="check">
            <input v-model="form.releaseBlock" type="checkbox" name="release" :disabled="!dialog.canRelease" />
            {{ dialog.kind === 'repair-complete' ? '同时取消本维修关联不可售块' : '同时取消本维修关联不可售块' }}
          </label>
          <p class="f-note">默认保留库存块。其他维护、占用与营业安排仍需在资源库存中核对。</p>
          <label>{{ dialog.kind === 'repair-complete' ? '维修结果' : '取消原因' }}
            <textarea v-model="form.note" name="reason" rows="3" maxlength="1000" required></textarea>
          </label>
        </template>

        <p v-if="dialog?.error" class="error-text" role="alert" data-test="dialog-error">{{ dialog.error }}</p>

        <div class="row modal-actions">
          <button class="btn" type="button" data-test="dialog-cancel" @click="closeDialog">
            {{ dialog && dialog.submit ? '取消' : '关闭详情' }}
          </button>
          <button v-if="dialog?.submit" class="btn primary" type="submit" data-test="dialog-confirm" :disabled="dialog.busy">
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { assetsApi, type AssetContext } from '@/api/assets'
import type { AssetInspectionRecord, AssetItem, AssetLifecycleEvent, AssetRepairRecord } from '@/api/types'
import { siteOpsApi, type SiteIncidentLog, type SiteShift, type SiteShiftHandover, type SiteTask } from '@/api/siteOps'
import { venueApi } from '@/api/venue'
import type { VenueSpace } from '@/api/types'
import { ISOLATED_REQUEST as isolated, formatStampDateTime, money, monthDayWithWeekday, restoreFocusChain } from '../adminShared'
import {
  ASSET_TARGET_STATUSES,
  INCIDENT_TYPE_LABELS,
  INSPECTION_RESULT_LABELS,
  PRIORITY_LABELS,
  RISK_LEVEL_LABELS,
  ROLE_CODE_LABELS,
  SEVERITY_LABELS,
  assetStatusLabel,
  assetTypeOptions,
  blockLinkText,
  blockWindowError,
  handoverForShift,
  hourMinute,
  incidentResolution,
  incidentStatusLabel,
  incidentTypeLabel,
  initialTabFromRoute,
  isMutableAsset,
  TASK_TYPE_LABELS,
  isStartableTask,
  isTerminalTask,
  latestInspectionOf,
  lifecycleLine,
  openIncidents,
  openRepairOf,
  openTasks,
  readableError,
  repairActions,
  repairStatusLabel,
  responsibleText,
  severityLabel,
  shiftAction,
  shiftStatusLabel,
  shiftTaskOrder,
  shiftTimeRange,
  spaceName,
  supportsBlockLink,
  taskNote,
  taskResultEndpoint,
  taskStatusLabel,
  taskTypeLabel,
  handoverStatusLabel,
  priorityLabel,
  eventLine,
  dayKey,
  type SiteOpsTab
} from './siteOpsShared'

type SurfaceState = 'loading' | 'ready' | 'error' | 'lock'
type DialogKind =
  | 'clock-in' | 'clock-out' | 'clock-blocked' | 'shift-form'
  | 'task-form' | 'task-detail' | 'handover-form' | 'handover-accept'
  | 'incident-form' | 'incident-resolve' | 'incident-detail'
  | 'asset-form' | 'asset-status' | 'inspection'
  | 'repair-form' | 'repair-start' | 'repair-complete' | 'repair-cancel'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  targetText: string
  bodyText: string
  badgeText: string
  taskOpen: boolean
  canStart: boolean
  canRelease: boolean
}

const TABS: Array<{ key: SiteOpsTab; label: string }> = [
  { key: 'shift', label: '当班日志' },
  { key: 'incidents', label: '现场异常' },
  { key: 'assets', label: '设备档案' }
]

const route = useRoute()
const auth = useAuthStore()
const app = useAppStore()
const venueAccess = useVenueAccessStore()
const rootEl = ref<HTMLElement | null>(null)
const dialogEl = ref<HTMLDialogElement | null>(null)
let lastTrigger: HTMLElement | null = null
let lastTriggerTest: string | null = null

const canSite = computed(() => auth.hasPermission('site:ops:manage'))
const canAsset = computed(() => auth.hasPermission('asset:manage'))
const canMaintain = computed(() => auth.hasPermission('asset:maintenance:manage'))

const tab = ref<SiteOpsTab>(initialTabFromRoute(route?.name))
const surface = ref<SurfaceState>('loading')

const shifts = ref<SiteShift[]>([])
const tasks = ref<SiteTask[]>([])
const handovers = ref<SiteShiftHandover[]>([])
const incidents = ref<SiteIncidentLog[]>([])
const assets = ref<AssetItem[]>([])
const inspections = ref<AssetInspectionRecord[]>([])
const repairs = ref<AssetRepairRecord[]>([])
const assetEvents = ref<AssetLifecycleEvent[]>([])
const assetContext = ref<AssetContext | null>(null)
const spaceList = ref<VenueSpace[]>([])
const selectedShiftId = ref<number | null>(null)
const selectedAssetId = ref<number | null>(null)
const dialog = ref<DialogState | null>(null)

const form = reactive({
  shiftName: '',
  roleCode: 'FRONT_DESK',
  assignedUserId: undefined as number | undefined,
  startAt: '',
  endAt: '',
  taskTitle: '',
  taskType: 'INSPECTION',
  priority: 'NORMAL',
  dueAt: '',
  taskAssetId: undefined as number | undefined,
  result: 'DONE',
  note: '',
  fromShiftId: undefined as number | undefined,
  toShiftId: 0 as number,
  fromUserId: undefined as number | undefined,
  incidentSummary: '',
  assetIssueSummary: '',
  description: '',
  occurredAt: '',
  severity: 'MEDIUM',
  incidentType: 'ASSET_FAILURE',
  incidentAssetId: undefined as number | undefined,
  assetName: '',
  assetType: 'EQUIPMENT',
  serialNo: '',
  spaceId: 0,
  toStatus: 'IN_USE',
  linkBlock: false,
  blockStart: '',
  blockEnd: '',
  releaseBlock: false,
  inspectionResult: 'PASS',
  riskLevel: 'LOW',
  cost: undefined as number | undefined,
  actualCost: undefined as number | undefined
})

let taskTarget: SiteTask | null = null
let incidentTarget: SiteIncidentLog | null = null
let repairTarget: AssetRepairRecord | null = null

const todayLabel = computed(() => monthDayWithWeekday(new Date().toISOString()) || '')
const activeVenueId = computed(() => venueAccess.activeVenueId)

const currentShift = computed<SiteShift | null>(() => {
  const list = shifts.value
  const pinned = list.find((item) => item.id === selectedShiftId.value)
  if (pinned) return pinned
  return (
    list.find((item) => item.status === 'IN_PROGRESS') ??
    list.find((item) => item.status === 'PLANNED') ??
    list[0] ??
    null
  )
})

const shiftTasks = computed(() => shiftTaskOrder(tasks.value))
const openTaskCount = computed(() => openTasks(tasks.value).length)
const openIncidentCount = computed(() => openIncidents(incidents.value).length)
const currentHandover = computed(() => handoverForShift(handovers.value, currentShift.value?.id))
const handoverStatusText = computed(() => {
  const handover = currentHandover.value
  if (handover) return handoverStatusLabel(handover.status)
  return handoverStatusLabel(currentShift.value?.handoverStatus)
})
const handoverNote = computed(() => {
  const handover = currentHandover.value
  if (!handover) return ''
  return [handover.note, handover.incidentSummary, handover.assetIssueSummary].filter(Boolean).join('；')
})
const receiverShiftText = computed(() => {
  const handover = currentHandover.value
  if (!handover?.toShiftId) return '尚未选择接收班次'
  const target = shifts.value.find((item) => item.id === handover.toShiftId)
  return target ? `${target.shiftName} ${shiftTimeRange(target)}` : `班次 #${handover.toShiftId}`
})
const receiverUserText = computed(() => {
  const handover = currentHandover.value
  return handover?.toUserId ? assigneeText(handover.toUserId) : '尚未选择接收人'
})

const handoverTargetShifts = computed(() => shifts.value.filter((item) => item.id !== form.fromShiftId))
const handoverTargetText = computed(() => {
  const target = handoverTargetShifts.value.find((item) => item.id === form.toShiftId)
  if (!target) return '尚未选择接收班次'
  return `${target.shiftName} ${shiftTimeRange(target)} · ${assigneeText(target.assignedUserId)}`
})

const incidentList = computed(() => incidents.value)
const assetList = computed(() => assets.value)
const selectedAsset = computed<AssetItem | null>(() => {
  const list = assets.value
  if (!list.length) return null
  const pinned = list.find((item) => item.id === selectedAssetId.value)
  return pinned ?? list[0]
})
const assetMutable = computed(() => isMutableAsset(selectedAsset.value))
const openRepair = computed(() => (canMaintain.value ? openRepairOf(repairs.value, selectedAsset.value?.id) : null))
const lastRepair = computed(() => {
  if (!canMaintain.value || !selectedAsset.value) return null
  const done = repairs.value
    .filter((item) => item.assetId === selectedAsset.value?.id && !['REQUESTED', 'IN_PROGRESS'].includes(item.status ?? ''))
    .sort((left, right) => String(right.updateTime ?? right.createTime ?? '').localeCompare(String(left.updateTime ?? left.createTime ?? '')))
  return done[0] ?? null
})
const latestInspection = computed(() => (canMaintain.value ? latestInspectionOf(inspections.value, selectedAsset.value?.id) : null))
const inspectionText = computed(() => {
  const record = latestInspection.value
  if (!record) return '还没有巡检记录'
  return `${formatStampDateTime(record.inspectedAt)} · ${inspectionResultLabelOf(record.inspectionResult)}`
})
const repairText = computed(() => (openRepair.value ? repairStatusLabel(openRepair.value.status) : '无开放维修'))
const spaceNames = computed(() => new Map(spaceList.value.map((space) => [space.id, space.name])))
const typeOptions = computed(() => assetTypeOptions(assets.value))

function inspectionResultLabelOf(value?: string | null): string {
  return INSPECTION_RESULT_LABELS[value ?? ''] ?? value ?? ''
}

function assigneeText(userId?: number | null): string {
  return userId ? `值班员 #${userId}` : '未指派'
}

function clockInText(shift: SiteShift): string {
  return shift.actualClockInTime ? hourMinute(shift.actualClockInTime) : '尚未签到'
}

function clockOutText(shift: SiteShift): string {
  return shift.actualClockOutTime ? ` · 签退 ${hourMinute(shift.actualClockOutTime)}` : ''
}

function taskTimeLine(task: SiteTask): string {
  const due = hourMinute(task.dueTime)
  const type = taskTypeLabel(task.taskType)
  const priority = priorityLabel(task.priority)
  return due ? `${due} 前 · ${type} · ${priority}优先级` : `${type} · ${priority}优先级`
}

function spaceText(spaceId?: number): string {
  return spaceName(spaceNames.value, spaceId)
}

function isOpenIncident(incident: SiteIncidentLog): boolean {
  return OPEN_INCIDENT.includes(incident.status ?? '')
}

const OPEN_INCIDENT = ['OPEN', 'IN_PROGRESS']

onMounted(() => {
  void bootstrap()
})

watch(
  () => venueAccess.scopeChangedAt,
  () => {
    void bootstrap()
  }
)

async function bootstrap(): Promise<void> {
  surface.value = 'loading'
  if (!venueAccess.venues.length) {
    try {
      await venueAccess.refresh()
    } catch {
      // 壳层锁定态兜底
    }
  }
  const venueId = activeVenueId.value
  if (!venueId) {
    surface.value = venueAccess.venues.length ? 'error' : 'lock'
    return
  }
  const emptyPage = { records: [] as never[], total: 0, pageNum: 1, pageSize: 100 }
  try {
    const [shiftPage, assetPage, spaceRows] = await Promise.all([
      canSite.value
        ? siteOpsApi.shifts({ venueId, date: dayKey(), pageNum: 1, pageSize: 100 }, isolated)
        : Promise.resolve({ ...emptyPage, records: [] as SiteShift[] }),
      canAsset.value
        ? assetsApi.list({ venueId, pageNum: 1, pageSize: 100 }, isolated)
        : Promise.resolve({ ...emptyPage, records: [] as AssetItem[] }),
      venueId ? venueApi.spaces(venueId, isolated).catch(() => [] as VenueSpace[]) : Promise.resolve([] as VenueSpace[])
    ])
    shifts.value = shiftPage.records
    assets.value = assetPage.records
    spaceList.value = spaceRows
    selectedShiftId.value = preferredShift(shifts.value)
    selectedAssetId.value = assets.value[0]?.id ?? null
    await loadShiftScoped(venueId)
    await loadAssetScoped(venueId)
    surface.value = 'ready'
  } catch (error) {
    surface.value = 'error'
    app.showToast(readableError(error, '现场与资产数据暂时无法读取。'))
  }
}

function preferredShift(list: SiteShift[]): number | null {
  const active = list.find((item) => item.status === 'IN_PROGRESS') ?? list.find((item) => item.status === 'PLANNED') ?? list[0]
  return active?.id ?? null
}

async function loadShiftScoped(venueId: number): Promise<void> {
  if (!canSite.value) {
    tasks.value = []
    handovers.value = []
    incidents.value = []
    return
  }
  const scope: Record<string, unknown> = { venueId, pageNum: 1, pageSize: 100 }
  if (currentShift.value?.id) scope.shiftId = currentShift.value.id
  const [taskPage, handoverPage, incidentPage] = await Promise.all([
    siteOpsApi.tasks(scope, isolated),
    siteOpsApi.handovers(scope, isolated),
    siteOpsApi.incidents(scope, isolated)
  ])
  tasks.value = taskPage.records
  handovers.value = handoverPage.records
  incidents.value = incidentPage.records
}

async function loadAssetScoped(venueId: number): Promise<void> {
  if (!canAsset.value) {
    assetEvents.value = []
    return
  }
  const assetId = selectedAsset.value?.id
  const [eventPage] = await Promise.all([
    assetId ? assetsApi.events(assetId, { pageNum: 1, pageSize: 20 }, isolated) : Promise.resolve({ records: [] as AssetLifecycleEvent[], total: 0, pageNum: 1, pageSize: 20 })
  ])
  assetEvents.value = eventPage.records
  if (!canMaintain.value) {
    inspections.value = []
    repairs.value = []
    return
  }
  const [inspectionPage, repairPage] = await Promise.all([
    assetsApi.inspections({ venueId, pageNum: 1, pageSize: 100 }, isolated),
    assetsApi.repairs({ venueId, pageNum: 1, pageSize: 100 }, isolated)
  ])
  inspections.value = inspectionPage.records
  repairs.value = repairPage.records
}

function reload(): void {
  void bootstrap()
}

function switchTab(next: SiteOpsTab): void {
  tab.value = next
}

async function selectAsset(assetId: number): Promise<void> {
  selectedAssetId.value = assetId
  const venueId = activeVenueId.value
  if (venueId) await loadAssetScoped(venueId)
}

async function jumpToAsset(assetId: number): Promise<void> {
  if (!canAsset.value) return
  tab.value = 'assets'
  await selectAsset(assetId)
}

function openDialog(next: DialogState, trigger?: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement instanceof HTMLElement ? document.activeElement : null)
  lastTriggerTest = lastTrigger?.getAttribute('data-test') ?? null
  dialog.value = next
  void nextTick(() => {
    if (dialogEl.value && !dialogEl.value.open) dialogEl.value.showModal()
  })
}

function closeDialog(): void {
  dialogEl.value?.close()
}

/** 焦点恢复链照抄 ref/f/common.js:2：原触发元素 → 重新渲染后的同 data-test 元素 → 当前页签（B3-T72）。 */
function onDialogClose(): void {
  const trigger = lastTrigger
  const test = lastTriggerTest
  dialog.value = null
  lastTrigger = null
  lastTriggerTest = null
  restoreFocusChain({
    rootSelector: '.sga-siteops',
    trigger,
    triggerTest: test,
    tabSelector: '.sga-siteops .f-tabs button[aria-current="true"]'
  })
}

/** 弹层 Tab 环照抄 ref/f/common.js:2：原生模态在第 6 次 Tab 会把焦点交给 body，这里首尾相接。 */
function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Tab') return
  const dialogNode = dialogEl.value
  if (!dialogNode) return
  const focusable = [...dialogNode.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]'
  )]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && active === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

function newDialog(kind: DialogKind, title: string, submit = ''): DialogState {
  return {
    kind,
    title,
    submit,
    error: '',
    busy: false,
    targetText: '',
    bodyText: '',
    badgeText: '',
    taskOpen: false,
    canStart: false,
    canRelease: false
  }
}

function resetForm(): void {
  form.shiftName = ''
  form.roleCode = 'FRONT_DESK'
  form.assignedUserId = undefined
  form.startAt = ''
  form.endAt = ''
  form.taskTitle = ''
  form.taskType = 'INSPECTION'
  form.priority = 'NORMAL'
  form.dueAt = ''
  form.taskAssetId = undefined
  form.result = 'DONE'
  form.note = ''
  form.fromShiftId = undefined
  form.toShiftId = 0
  form.fromUserId = undefined
  form.incidentSummary = ''
  form.assetIssueSummary = ''
  form.description = ''
  form.occurredAt = ''
  form.severity = 'MEDIUM'
  form.incidentType = 'ASSET_FAILURE'
  form.incidentAssetId = undefined
  form.assetName = ''
  form.assetType = 'EQUIPMENT'
  form.serialNo = ''
  form.spaceId = 0
  form.toStatus = 'IN_USE'
  form.linkBlock = false
  form.blockStart = ''
  form.blockEnd = ''
  form.releaseBlock = false
  form.inspectionResult = 'PASS'
  form.riskLevel = 'LOW'
  form.cost = undefined
  form.actualCost = undefined
}

function toLocalDateTime(value: string): string | undefined {
  if (!value) return undefined
  return value.length === 16 ? `${value}:00` : value
}

function shiftDefaults(shift?: SiteShift | null): { startAt: string; endAt: string } {
  const base = shift?.plannedStartTime ? new Date(shift.plannedStartTime) : new Date()
  const day = dayKey(base)
  const startHour = shift?.plannedStartTime ? hourMinute(shift.plannedStartTime) : '09:00'
  const endHour = shift?.plannedEndTime ? hourMinute(shift.plannedEndTime) : '17:00'
  return { startAt: `${day}T${startHour}`, endAt: `${day}T${endHour}` }
}

function openClockIn(shift: SiteShift, event?: Event): void {
  const state = newDialog('clock-in', '班次签到', '确认签到')
  state.targetText = `${shift.shiftName} · ${shiftTimeRange(shift)}`
  openDialog(state, event ?? null)
}

function tryClockOut(event?: Event): void {
  const shift = currentShift.value
  if (!shift) return
  if (openTaskCount.value && currentHandover.value?.status !== 'ACCEPTED') {
    const state = newDialog('clock-blocked', '暂时不能签退')
    state.targetText = `本班仍有 ${openTaskCount.value} 项开放任务，交接尚未接收。请完成任务或先填写并接收交接记录。`
    openDialog(state, event ?? null)
    return
  }
  const state = newDialog('clock-out', '班次签退', '确认签退')
  state.targetText = `${shift.shiftName} · ${shiftTimeRange(shift)}`
  openDialog(state, event ?? null)
}

function openShiftForm(existing: boolean, event?: Event): void {
  const shift = existing ? currentShift.value : null
  resetForm()
  const defaults = shiftDefaults(shift)
  form.shiftName = shift?.shiftName ?? '夜间保障'
  form.roleCode = shift?.roleCode ?? 'FRONT_DESK'
  form.assignedUserId = shift?.assignedUserId
  form.startAt = existing ? defaults.startAt : shiftDefaults(null).startAt
  form.endAt = existing ? shiftDefaults(shift).endAt : defaults.endAt
  const state = newDialog('shift-form', existing ? '编辑当前班次' : '新增现场班次', '保存班次')
  state.targetText = shift?.shiftNo ?? ''
  openDialog(state, event ?? null)
}

function openTaskForm(event?: Event): void {
  resetForm()
  const state = newDialog('task-form', '记录当班任务', '记录任务')
  openDialog(state, event ?? null)
}

function openTaskDetail(task: SiteTask, event?: Event): void {
  taskTarget = task
  resetForm()
  const open = !isTerminalTask(task)
  const state = newDialog('task-detail', '当班任务', open ? '保存处理结果' : '')
  state.targetText = task.title ?? ''
  state.bodyText = taskNote(task) || '尚未填写处理结果。'
  state.badgeText = taskStatusLabel(task.status)
  state.taskOpen = open
  state.canStart = isStartableTask(task)
  openDialog(state, event ?? null)
}

function openHandoverForm(event?: Event): void {
  const shift = currentShift.value
  resetForm()
  form.fromShiftId = shift?.id
  form.fromUserId = shift?.assignedUserId
  const state = newDialog('handover-form', '填写本班交接', '提交交接')
  state.targetText = shift ? `${shift.shiftName} · ${assigneeText(shift.assignedUserId)}` : ''
  openDialog(state, event ?? null)
}

function openHandoverAccept(event?: Event): void {
  const state = newDialog('handover-accept', '接收交接', '确认接收')
  state.targetText = handoverNote.value
  openDialog(state, event ?? null)
}

function openIncidentForm(event?: Event): void {
  resetForm()
  const now = new Date()
  form.occurredAt = `${dayKey(now)}T${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
  const state = newDialog('incident-form', '记录现场异常', '记录异常')
  openDialog(state, event ?? null)
}

function openIncidentDetail(incident: SiteIncidentLog, event?: Event): void {
  incidentTarget = incident
  resetForm()
  if (isOpenIncident(incident)) {
    const state = newDialog('incident-resolve', '记录异常处理结果', '标记已解决')
    state.targetText = incident.description ?? ''
    openDialog(state, event ?? null)
    return
  }
  const state = newDialog('incident-detail', '异常处理记录')
  state.targetText = incident.description ?? ''
  state.bodyText = incidentResolution(incident)
  state.badgeText = `${incidentStatusLabel(incident.status)} · 只读`
  openDialog(state, event ?? null)
}

function openAssetForm(existing: boolean, event?: Event): void {
  const asset = existing ? selectedAsset.value : null
  resetForm()
  form.assetName = asset?.name ?? ''
  form.assetType = asset?.assetType ?? 'EQUIPMENT'
  form.serialNo = asset?.serialNo ?? ''
  form.spaceId = asset?.spaceId ?? 0
  const state = newDialog('asset-form', existing ? '编辑设备档案' : '设备建档', '保存档案')
  state.targetText = asset?.assetNo ?? ''
  openDialog(state, event ?? null)
}

function openAssetStatus(event?: Event): void {
  const asset = selectedAsset.value
  if (!asset) return
  resetForm()
  const state = newDialog('asset-status', '变更资产状态', '确认状态变更')
  state.targetText = `${asset.name} · 当前${assetStatusLabel(asset.status)}`
  openDialog(state, event ?? null)
}

function openInspection(event?: Event): void {
  resetForm()
  const state = newDialog('inspection', '记录设备巡检', '保存巡检')
  openDialog(state, event ?? null)
}

function openRepairForm(event?: Event): void {
  const asset = selectedAsset.value
  if (!asset) return
  resetForm()
  const state = newDialog('repair-form', '发起设备维修', '提交维修')
  state.targetText = asset.name ?? ''
  openDialog(state, event ?? null)
}

function openRepairAction(kind: 'repair-start' | 'repair-complete' | 'repair-cancel', event?: Event): void {
  const repair = openRepair.value ?? lastRepair.value
  if (!repair) return
  repairTarget = repair
  resetForm()
  const asset = selectedAsset.value
  const titles = { 'repair-start': '开始维修', 'repair-complete': '完成设备维修', 'repair-cancel': '取消设备维修' }
  const submits = { 'repair-start': '开始维修', 'repair-complete': '完成维修', 'repair-cancel': '取消维修' }
  const state = newDialog(kind, titles[kind], submits[kind])
  state.targetText = asset ? `${asset.name} · ${repair.failureDescription ?? ''}` : repair.failureDescription ?? ''
  state.canRelease = repair.unavailableBlockId != null
  form.releaseBlock = false
  openDialog(state, event ?? null)
}

async function startTask(): Promise<void> {
  if (!taskTarget || !dialog.value) return
  const result = await runAction(() => siteOpsApi.startTask(taskTarget!.id, isolated), '任务已开始')
  if (result) {
    closeDialog()
    await refreshScoped()
  }
}

function editTask(): void {
  if (!taskTarget) return
  const task = taskTarget
  const state = newDialog('task-form', '编辑当班任务', '保存任务')
  form.taskTitle = task.title ?? ''
  form.taskType = task.taskType ?? 'INSPECTION'
  form.priority = task.priority ?? 'NORMAL'
  form.dueAt = task.dueTime ? `${dayKey(new Date(task.dueTime))}T${hourMinute(task.dueTime)}` : ''
  form.taskAssetId = task.assetId
  state.targetText = String(task.id)
  openDialog(state)
}

async function refreshScoped(): Promise<void> {
  const venueId = activeVenueId.value
  if (!venueId) return
  await Promise.all([loadShiftScoped(venueId), loadAssetScoped(venueId)])
}

async function runAction(call: () => Promise<unknown>, toast: string): Promise<boolean> {
  const state = dialog.value
  if (!state) return false
  state.busy = true
  state.error = ''
  try {
    await call()
    app.showToast(toast)
    return true
  } catch (error) {
    state.error = readableError(error, '这次提交没有成功，内容已保留。请重试。')
    return false
  } finally {
    state.busy = false
  }
}

async function confirmDialog(): Promise<void> {
  const state = dialog.value
  if (!state) return
  const venueId = activeVenueId.value
  if (!venueId) return

  if (state.kind === 'clock-in' && currentShift.value) {
    if (await runAction(() => siteOpsApi.clockIn(currentShift.value!.id, isolated), '班次已签到')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'clock-out' && currentShift.value) {
    if (await runAction(() => siteOpsApi.clockOut(currentShift.value!.id, isolated), '本班已签退')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'clock-blocked') {
    closeDialog()
    return
  }
  if (state.kind === 'shift-form') {
    if (!form.shiftName.trim()) {
      state.error = '请填写班次名称。'
      return
    }
    if (!form.assignedUserId) {
      state.error = '请填写指派人员 ID。'
      return
    }
    if (!form.startAt || !form.endAt || form.startAt >= form.endAt) {
      state.error = '计划开始必须早于结束。'
      return
    }
    const payload = {
      venueId,
      shiftDate: form.startAt.slice(0, 10),
      shiftName: form.shiftName.trim(),
      roleCode: form.roleCode,
      assignedUserId: form.assignedUserId,
      plannedStartTime: toLocalDateTime(form.startAt),
      plannedEndTime: toLocalDateTime(form.endAt)
    }
    const editing = state.targetText !== ''
    const call = editing
      ? siteOpsApi.updateShift(Number(currentShift.value?.id), payload, isolated)
      : siteOpsApi.createShift(payload, isolated)
    if (await runAction(() => call, editing ? '班次已更新' : '班次已登记')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'task-form') {
    if (!form.taskTitle.trim()) {
      state.error = '请填写任务标题。'
      return
    }
    const shiftId = currentShift.value?.id
    const payload: Record<string, unknown> = {
      venueId,
      shiftId,
      title: form.taskTitle.trim(),
      taskType: form.taskType,
      priority: form.priority,
      dueTime: toLocalDateTime(form.dueAt),
      assetId: form.taskAssetId || undefined
    }
    const editing = state.targetText !== ''
    const call = editing
      ? siteOpsApi.updateTask(Number(state.targetText), payload, isolated)
      : siteOpsApi.createTask(payload, isolated)
    if (await runAction(() => call, editing ? '任务已更新' : '任务已记录')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'task-detail' && taskTarget) {
    if (!form.note.trim()) {
      state.error = '请填写处理说明。'
      return
    }
    const endpoint = taskResultEndpoint(form.result)
    const call =
      endpoint === 'cancel'
        ? siteOpsApi.cancelTask(taskTarget.id, { reason: form.note.trim() }, isolated)
        : siteOpsApi.completeTask(
            taskTarget.id,
            {
              completionNote: form.note.trim(),
              exceptionCode: form.result === 'EXCEPTION' ? 'SITE_TASK_EXCEPTION' : undefined
            },
            isolated
          )
    if (await runAction(() => call, '任务结果已保存')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'handover-form') {
    if (!form.fromShiftId || !form.fromUserId) {
      state.error = '当前班次缺少可交接的指派人员，请先编辑班次。'
      return
    }
    const target = handoverTargetShifts.value.find((item) => item.id === form.toShiftId)
    if (!target) {
      state.error = '请选择本班之外的接收班次。'
      return
    }
    if (!target.assignedUserId) {
      state.error = '接收班次尚未指派人员，请先为该班次指派人员。'
      return
    }
    if (!form.note.trim()) {
      state.error = '请填写处理说明。'
      return
    }
    const payload = {
      venueId,
      fromShiftId: form.fromShiftId,
      toShiftId: target.id,
      fromUserId: form.fromUserId,
      toUserId: target.assignedUserId,
      incidentSummary: form.incidentSummary.trim() || undefined,
      assetIssueSummary: form.assetIssueSummary.trim() || undefined,
      note: form.note.trim()
    }
    if (await runAction(() => siteOpsApi.submitHandover(payload, isolated), '交接记录已提交')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'handover-accept' && currentHandover.value) {
    if (await runAction(() => siteOpsApi.acceptHandover(currentHandover.value!.id, isolated), '交接已接收')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'incident-form') {
    if (!form.description.trim()) {
      state.error = '请填写异常描述。'
      return
    }
    if (!form.note.trim()) {
      state.error = '请填写已采取的处置措施。'
      return
    }
    const payload = {
      venueId,
      shiftId: currentShift.value?.id,
      assetId: form.incidentAssetId || undefined,
      incidentType: form.incidentType,
      severity: form.severity,
      occurredAt: toLocalDateTime(form.occurredAt),
      description: `${form.description.trim()}${form.note.trim() ? ` · 处置：${form.note.trim()}` : ''}`
    }
    if (await runAction(() => siteOpsApi.recordIncident(payload, isolated), '现场异常已记录')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'incident-resolve' && incidentTarget) {
    if (!form.note.trim()) {
      state.error = '请填写处理结果。'
      return
    }
    if (await runAction(() => siteOpsApi.resolveIncident(incidentTarget!.id, { resolution: form.note.trim() }, isolated), '异常已标记解决')) {
      closeDialog()
      await refreshScoped()
    }
    return
  }
  if (state.kind === 'incident-detail') {
    closeDialog()
    return
  }
  if (state.kind === 'asset-form') {
    if (!form.assetName.trim()) {
      state.error = '请填写资产名称。'
      return
    }
    const payload: Record<string, unknown> = {
      venueId,
      spaceId: form.spaceId || undefined,
      assetType: form.assetType,
      name: form.assetName.trim(),
      serialNo: form.serialNo.trim() || undefined
    }
    const editing = state.targetText !== ''
    const call = editing
      ? assetsApi.update(Number(selectedAsset.value?.id), payload, isolated)
      : assetsApi.create(payload, isolated)
    if (await runAction(() => call, editing ? '设备档案已更新' : '设备已建档')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'asset-status' && selectedAsset.value) {
    const blockError = blockWindowError(form.linkBlock, form.blockStart, form.blockEnd)
    if (blockError) {
      state.error = blockError
      return
    }
    if (form.linkBlock && !supportsBlockLink(form.toStatus)) {
      state.error = '仅停用或维修中可创建不可售联动。'
      return
    }
    if (form.releaseBlock && form.toStatus !== 'IN_USE') {
      state.error = '只有恢复使用时可释放资产关联块。'
      return
    }
    if (!form.note.trim()) {
      state.error = '请填写处理说明。'
      return
    }
    const payload = {
      toStatus: form.toStatus,
      reasonCode: 'MANUAL',
      reasonText: form.note.trim(),
      blockStartTime: form.linkBlock ? toLocalDateTime(form.blockStart) : undefined,
      blockEndTime: form.linkBlock ? toLocalDateTime(form.blockEnd) : undefined,
      releaseUnavailableBlock: form.releaseBlock
    }
    if (await runAction(() => assetsApi.changeStatus(selectedAsset.value!.id, payload, isolated), '资产状态已变更')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'inspection' && selectedAsset.value) {
    if (!form.note.trim()) {
      state.error = '请填写巡检说明。'
      return
    }
    const payload = {
      inspectionResult: form.inspectionResult,
      riskLevel: form.riskLevel,
      issueSummary: form.note.trim(),
      inspectedAt: new Date().toISOString().slice(0, 19)
    }
    if (await runAction(() => assetsApi.recordInspection(selectedAsset.value!.id, payload, isolated), '巡检记录已保存')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'repair-form' && selectedAsset.value) {
    const blockError = blockWindowError(form.linkBlock, form.blockStart, form.blockEnd)
    if (blockError) {
      state.error = blockError
      return
    }
    if (form.cost != null && form.cost < 0) {
      state.error = '费用不能为负数。'
      return
    }
    if (!form.note.trim()) {
      state.error = '请填写故障描述。'
      return
    }
    const payload = {
      failureDescription: form.note.trim(),
      estimatedCostAmount: form.cost ?? 0,
      blockStartTime: form.linkBlock ? toLocalDateTime(form.blockStart) : undefined,
      blockEndTime: form.linkBlock ? toLocalDateTime(form.blockEnd) : undefined
    }
    if (await runAction(() => assetsApi.requestRepair(selectedAsset.value!.id, payload, isolated), '维修已发起')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'repair-start' && repairTarget) {
    if (await runAction(() => assetsApi.startRepair(repairTarget!.id, isolated), '维修已开始')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'repair-complete' && repairTarget) {
    if (form.actualCost != null && form.actualCost < 0) {
      state.error = '费用不能为负数。'
      return
    }
    if (!form.note.trim()) {
      state.error = '请填写维修结果。'
      return
    }
    const payload = { actualCostAmount: form.actualCost ?? 0, resolution: form.note.trim(), cancelUnavailableBlock: form.releaseBlock }
    if (await runAction(() => assetsApi.completeRepair(repairTarget!.id, payload, isolated), '维修已完成')) {
      closeDialog()
      await bootstrap()
    }
    return
  }
  if (state.kind === 'repair-cancel' && repairTarget) {
    if (!form.note.trim()) {
      state.error = '请填写取消原因。'
      return
    }
    const payload = { reason: form.note.trim(), cancelUnavailableBlock: form.releaseBlock }
    if (await runAction(() => assetsApi.cancelRepair(repairTarget!.id, payload, isolated), '维修已取消')) {
      closeDialog()
      await bootstrap()
    }
  }
}
</script>
