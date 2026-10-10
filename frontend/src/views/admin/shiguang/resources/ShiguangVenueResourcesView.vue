<template>
  <div ref="rootEl" class="sga-venue" data-test="sga-venue-root">
    <div class="e-head">
      <div>
        <div class="e-kicker">场馆运营 · 资源与可用性</div>
        <h1>场地资源与库存管理</h1>
        <p>先看一天里的资源安排，再维护空间与预订规则。把已有预约、维护和不可售区间放回同一份可用性记录。</p>
      </div>
    </div>

    <div class="e-tabs" role="group" aria-label="资源工作面">
      <button
        v-for="item in TABS"
        :id="`tab-${item.key}`"
        :key="item.key"
        :data-test="`tab-${item.key}`"
        type="button"
        :aria-pressed="tab === item.key"
        @click="switchTab(item.key)"
      >{{ item.label }}</button>
    </div>

    <div v-if="surface === 'loading'" class="e-empty" role="status" data-test="surface-loading">
      <h2>正在读取资源与库存</h2>
      <p>当前尚无本次结果，请完成读取后继续。</p>
    </div>
    <div v-else-if="surface === 'forbidden'" class="e-empty" data-test="surface-forbidden">
      <h2>没有资源模块访问权限</h2>
      <p>资源或日历的管理权限都不具备时，不能读取本工作面。可返回工作台或联系平台管理员。</p>
      <button class="btn" type="button" @click="bootstrap">重新读取</button>
    </div>
    <div v-else-if="surface === 'error'" class="e-empty" role="alert" data-test="surface-error">
      <h2>本次读取未完成</h2>
      <p>未取得当前资源结果，请重新读取。</p>
      <button class="btn primary" type="button" @click="bootstrap">重新读取</button>
    </div>
    <div v-else-if="!spaces.length" class="e-empty" data-test="surface-empty">
      <h2>当前场馆没有资源</h2>
      <p>可以重新读取，或由资源管理员登记新场地。</p>
      <button class="btn primary" type="button" @click="bootstrap">重新读取</button>
    </div>
    <template v-else>
      <div v-if="tab === 'inventory' && !canInventory" class="e-empty" data-test="denied-inventory">
        <h2>当前没有库存查看权限</h2>
        <p>资源或日历的管理权限不代表能够读取库存。</p>
        <button v-if="canResources" class="btn" type="button" @click="switchTab('resources')">查看可进入的资源工作面</button>
      </div>
      <div v-else-if="tab === 'resources' && !canResources" class="e-empty" data-test="denied-resources">
        <h2>当前没有资源规则读取与维护权限</h2>
        <p>物理容量、资源规则与座位配置需要资源管理权限。</p>
        <button v-if="canInventory" class="btn" type="button" @click="switchTab('inventory')">返回库存工作面</button>
      </div>
      <div v-else-if="tab === 'venue' && !canInventory" class="e-empty" data-test="denied-venue">
        <h2>需要库存查看权限核对营业现状</h2>
        <p>当前仅有维护动作权限，不能读取当天营业安排。</p>
      </div>
      <div v-else>
        <p v-if="stale" class="e-note" role="status" data-test="stale-note">资源或安排已变更，以下为先前读取的库存。请重新读取后再操作。</p>

        <!-- 当日可用性 -->
        <template v-if="tab === 'inventory'">
          <div class="e-topline">
            <div>
              <h2>{{ venueName }} · 当日可用性</h2>
              <p>按空间与时间交叉核对，点击一个时段查看状态原因。</p>
            </div>
            <div class="e-tools">
              <label for="e-date">运营日期<input
                id="e-date"
                v-model="date"
                data-test="inventory-date"
                type="date"
                @change="onDateChange"
              ></label>
              <button class="btn" id="e-refresh" data-test="inventory-refresh" type="button" @click="refreshInventory">重新读取</button>
            </div>
          </div>
          <div class="e-legend">
            <span><i class="e-dot"></i>可预约</span>
            <span><i class="e-dot reserved"></i>已预约</span>
            <span><i class="e-dot hold"></i>人工保留 / 维护</span>
            <span><i class="e-dot closed"></i>关闭 / 缓冲</span>
          </div>
          <div class="e-matrix-wrap" tabindex="0" aria-label="场地与小时时段矩阵，可横向滚动" data-test="inventory-matrix">
            <table class="e-matrix">
              <thead>
                <tr>
                  <th>场地 / 预订方式</th>
                  <th v-for="hour in hours" :key="hour" scope="col">{{ pad(hour) }}:00</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in matrixRows" :key="row.space.id">
                  <th scope="row">
                    <strong>{{ row.space.name }}</strong>
                    <span>{{ row.space.code || `#${row.space.id}` }} · {{ ruleModeLabel(row.space) }}</span>
                  </th>
                  <td v-for="cell in row.cells" :key="cell.hour">
                    <button
                      v-if="cell.slot"
                      :id="`slot-${row.space.id}-${cell.hour}`"
                      class="e-slot"
                      type="button"
                      :data-state="cell.slot.status"
                      :aria-pressed="selectedSpaceId === row.space.id && selectedHour === cell.hour"
                      :aria-label="`${row.space.name} ${cell.hour}:00至${cell.hour + 1}:00，${slotStatusLabel(cell.slot.status)}`"
                      @click="inspect(row.space.id, cell.hour)"
                    >{{ slotStatusLabel(cell.slot.status) }}</button>
                    <span v-else class="e-slot e-slot-none" aria-label="该时段没有库存数据">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="e-quiet" style="margin:12px 0">横向滚动查看全天。可约不代表全部空闲；此处不展示剩余人数或空闲座位数量。</p>

          <div id="e-inspector">
            <section class="e-inspector" aria-live="polite" data-test="slot-inspector">
              <div>
                <div class="e-kicker">所选时段 · {{ date }}</div>
                <h3>{{ pad(selectedHour) }}:00–{{ pad((selectedHour + 1) % 24) }}:00</h3>
                <p>{{ selectedSpace?.name }}</p>
              </div>
              <div>
                <strong>{{ inspectedSlot ? slotStatusLabel(inspectedSlot.status) : '没有库存数据' }}</strong>
                <p>{{ inspectedSlotReason }}</p>
                <p v-if="inspectedSlot?.reservationId">关联预约 #{{ inspectedSlot.reservationId }} · {{ inspectedSlot.reservationStatus }}</p>
              </div>
              <div class="e-actions">
                <button
                  class="btn"
                  id="e-maintenance"
                  data-test="open-maintenance"
                  type="button"
                  :disabled="!canResources || stale || !inspectedSlot"
                  @click="openIntervalDialog('maintenance')"
                >安排维护</button>
                <button
                  class="btn"
                  id="e-block"
                  data-test="open-block"
                  type="button"
                  :disabled="!canCalendar || stale || !inspectedSlot"
                  @click="openIntervalDialog('block')"
                >新增不可售</button>
              </div>
            </section>
          </div>

          <section class="e-section" data-test="inventory-records">
            <div class="e-topline">
              <div>
                <h2>维护与不可售记录</h2>
                <p>维护与人工保留分别记录来源；取消需要再次核对。</p>
              </div>
            </div>
            <template v-if="records.length">
              <article v-for="row in records" :key="`${row.kind}-${row.id}`" class="e-record">
                <div>
                  <span class="e-status" :class="{ warn: row.active && row.kind === 'block' && row.typeLabel !== '人工保留' }">{{ row.typeLabel }}</span>
                  <p style="margin-top:8px">{{ row.spaceName }}</p>
                </div>
                <div>
                  <h3>{{ row.reason }}</h3>
                  <p>{{ recordMetaLine(row) }}</p>
                </div>
                <button
                  v-if="row.active"
                  class="btn"
                  type="button"
                  :data-test="`cancel-${row.kind}`"
                  :disabled="stale || (row.kind === 'maintenance' ? !canResources : !canCalendar)"
                  @click="openCancelRecord(row)"
                >取消区间</button>
                <span v-else class="e-quiet">保留记录</span>
              </article>
            </template>
            <p v-else class="e-note">当前日期没有维护或不可售记录。</p>
          </section>
          <div class="e-preview-facts">
            <p class="e-quiet">库存为当前日期的读取结果；物理容量、预订容量与座位配置在“资源定义”中分别维护。</p>
          </div>
        </template>

        <!-- 资源定义 -->
        <template v-else-if="tab === 'resources'">
          <div class="e-topline">
            <div>
              <h2>资源定义</h2>
              <p>空间能容纳多少人，与怎样出售预约，是两组不同的规则。</p>
            </div>
            <button class="btn" data-test="new-space" type="button" :disabled="!canResources" @click="openSpaceForm(null)">新增场地</button>
          </div>
          <div class="e-resource-picker" role="group" aria-label="选择资源">
            <button
              v-for="space in spaces"
              :id="`resource-${space.id}`"
              :key="space.id"
              type="button"
              :aria-pressed="selectedSpaceId === space.id"
              @click="selectSpace(space.id)"
            >{{ space.name }}</button>
          </div>
          <div v-if="selectedSpace" class="e-rule-layout">
            <section>
              <div class="e-topline">
                <div>
                  <h2>{{ selectedSpace.name }}</h2>
                  <p>{{ selectedSpace.code || `#${selectedSpace.id}` }} · {{ selectedSpace.type }}</p>
                </div>
                <span class="e-status" :class="{ warn: selectedSpace.status !== 'ACTIVE' }">{{ spaceStatusText(selectedSpace) }}</span>
              </div>
              <div class="e-capacity">
                <div>
                  <label>物理容量</label>
                  <strong>{{ selectedSpace.capacity ?? '—' }}</strong>
                  <span>人 · 空间基础信息</span>
                </div>
                <div>
                  <label>预订方式</label>
                  <strong style="font-size:23px">{{ ruleModeLabel(selectedSpace) }}</strong>
                  <span>{{ ruleSubtitle(selectedSpace) }}</span>
                </div>
                <div>
                  <label>基础价格</label>
                  <strong>¥ {{ selectedSpace.pricePerHour }}</strong>
                  <span>每小时 · 不在此推算成交价</span>
                </div>
              </div>
              <dl class="e-definition">
                <dt>资源类型</dt>
                <dd>{{ resourceCategoryLabel(selectedSpace.resourceRule?.resourceCategory) || '未设置' }}</dd>
                <dt>设施说明</dt>
                <dd>{{ selectedSpace.facilityText || '—' }}</dd>
                <dt>场地介绍</dt>
                <dd>{{ selectedSpace.description || '—' }}</dd>
                <dt>当前规则版本</dt>
                <dd>{{ ruleVersionLabel(selectedSpace) }}</dd>
              </dl>
              <div class="e-actions" style="margin-top:26px">
                <button class="btn" data-test="edit-space" type="button" :disabled="!canResources" @click="openSpaceForm(selectedSpace)">编辑基础信息</button>
                <button class="btn" data-test="edit-rule" type="button" :disabled="!canResources" @click="openRuleDialog()">配置预订规则</button>
                <button class="btn" data-test="toggle-space" type="button" :disabled="!canResources" @click="openToggleSpace()">{{ selectedSpace.status === 'ACTIVE' ? '停用场地' : '启用场地' }}</button>
              </div>
              <section v-if="selectedSpace.resourceRule?.bookingMode === 'ASSIGNED_SEAT'" class="e-section">
                <h3>座位编码目录</h3>
                <p class="e-quiet">这是配置清单；位置仅用于阅读分组，不表示真实座位布局或当前空闲。</p>
                <div class="e-seats">
                  <div v-for="seat in selectedSpace.resourceRule.seats" :key="seat.id ?? seat.seatCode" class="e-seat">
                    {{ seat.seatCode }}<small>{{ seat.seatLabel }}</small>
                  </div>
                </div>
              </section>
            </section>
            <aside class="e-sidebar">
              <h3>三件事分别维护</h3>
              <p><strong>物理容量</strong><br>记录空间基础信息。修改它不会自动改写可预约容量。</p>
              <p><strong>预订规则</strong><br>整空间、按人数和指定座位有各自的分配方式。按人数上限独立填写。</p>
              <p><strong>时段库存</strong><br>来自当前日期的服务结果。改动规则后需要重新读取，不靠容量差额推算。</p>
              <p class="e-note">有未来有效预约时，切换模式、缩容或改变座位配置会被拒绝。</p>
            </aside>
          </div>
        </template>

        <!-- 场馆与营业安排 -->
        <template v-else>
          <div class="e-topline">
            <div>
              <h2>{{ venueName }}</h2>
              <p>场馆基础信息与某一天的营业安排分别维护。</p>
            </div>
            <div class="e-actions">
              <button class="btn" data-test="edit-venue" type="button" :disabled="!canResources" @click="openVenueForm(false)">编辑场馆信息</button>
              <button v-if="canSystem" class="btn" data-test="new-venue" type="button" @click="openVenueForm(true)">新增场馆</button>
            </div>
          </div>
          <div class="e-business">
            <section>
              <h3>场馆基础信息</h3>
              <dl class="e-definition">
                <dt>所在区域</dt><dd>{{ activeVenue?.area || '—' }}</dd>
                <dt>地址</dt><dd>{{ activeVenue?.address || '—' }}</dd>
                <dt>服务联系</dt><dd>{{ activeVenue?.phone || '—' }}</dd>
                <dt>常规营业</dt><dd>{{ venueHoursLabel(activeVenue?.openTime, activeVenue?.closeTime) }}</dd>
                <dt>场馆说明</dt><dd>{{ activeVenue?.description || '—' }}</dd>
              </dl>
            </section>
            <section class="e-business-side">
              <h3>当天营业安排</h3>
              <div class="e-field" style="max-width:240px;margin:16px 0">
                <label for="venue-date">运营日期</label>
                <input id="venue-date" v-model="date" data-test="venue-date" type="date" @change="onDateChange">
              </div>
              <span class="e-status">{{ businessStatusText(venueCalendar) }}</span>
              <div class="e-hours">{{ calendarHoursText(venueCalendar) }}</div>
              <p class="e-quiet">{{ calendarReasonText(venueCalendar) }}</p>
              <button class="btn" data-test="edit-calendar" type="button" :disabled="!canCalendar" @click="openCalendarDialog()">调整当天营业安排</button>
            </section>
          </div>
          <p class="e-note">关闭场馆或收紧营业时间可能与有效预约冲突。保存时核对结果，原预约不会因本页编辑自动消失。</p>
        </template>
      </div>
    </template>

    <dialog ref="dialogEl" class="e-dialog" aria-labelledby="e-dialog-title" @close="onDialogClose">
      <form v-if="dialog" @submit.prevent="confirmDialog">
        <div class="e-dialog-head">
          <h2 id="e-dialog-title">{{ dialog.title }}</h2>
          <button class="e-close" type="button" aria-label="关闭详情" @click="closeDialog">×</button>
        </div>
        <div class="e-dialog-body">
          <!-- 场地基础信息 -->
          <template v-if="dialog.kind === 'space-form'">
            <p class="e-quiet">{{ venueName }} · 只编辑基础信息，预订容量与座位规则另行配置。</p>
            <div class="e-form">
              <div class="e-field"><label for="s-name">场地名称</label><input id="s-name" v-model="form.name" data-test="s-name" required maxlength="60"></div>
              <div class="e-field"><label for="s-code">资源编号</label><input id="s-code" v-model="form.code" required maxlength="32"></div>
              <div class="e-field"><label for="s-type">业务类型</label><input id="s-type" v-model="form.type" required></div>
              <div class="e-field"><label for="s-capacity">物理容量（人）</label><input id="s-capacity" v-model.number="form.capacity" type="number" required min="1"></div>
              <div class="e-field"><label for="s-price">基础价格（元 / 小时）</label><input id="s-price" v-model.number="form.price" type="number" required min="0" step="0.01"></div>
              <div class="e-field e-span"><label for="s-facility">设施说明</label><input id="s-facility" v-model="form.facility"></div>
              <div class="e-field e-span"><label for="s-description">场地介绍</label><textarea id="s-description" v-model="form.description"></textarea></div>
            </div>
            <p class="e-note">物理容量不自动决定可预约人数。保存后请分别核对预订规则和当天库存。</p>
          </template>

          <!-- 预订规则 -->
          <template v-else-if="dialog.kind === 'rule'">
            <div class="e-form">
              <div class="e-field"><label for="r-category">资源类型</label>
                <select id="r-category" v-model="form.category">
                  <option v-for="(label, value) in RESOURCE_CATEGORY_OPTIONS" :key="value" :value="value">{{ label }}</option>
                </select>
              </div>
              <div class="e-field"><label for="r-mode">预订方式</label>
                <select id="r-mode" v-model="form.mode" data-test="r-mode">
                  <option v-for="(label, value) in BOOKING_MODE_OPTIONS" :key="value" :value="value">{{ label }}</option>
                </select>
              </div>
              <div id="r-capacity-field" v-show="form.mode === 'CAPACITY'" class="e-field e-span">
                <label for="r-capacity">预订容量上限（人）</label>
                <input id="r-capacity" v-model.number="form.capacityLimit" type="number" min="1" max="10000" :required="form.mode === 'CAPACITY'">
              </div>
              <div id="r-seats-field" v-show="form.mode === 'ASSIGNED_SEAT'" class="e-field e-span">
                <h3>当前版本的座位编码与名称</h3>
                <div>
                  <div v-for="(seat, index) in draftSeats" :key="index" class="e-seat-edit-row">
                    <input v-model="seat.seatCode" data-seat-code :aria-label="`座位 ${index + 1} 编码`" maxlength="64">
                    <input v-model="seat.seatLabel" data-seat-label :aria-label="`座位 ${index + 1} 名称`" maxlength="128">
                    <button class="e-close" type="button" :aria-label="`移除座位 ${index + 1}`" @click="removeSeat(index)">×</button>
                  </div>
                </div>
                <button class="btn" type="button" @click="addSeat">添加座位</button>
              </div>
              <div class="e-field e-span">
                <label for="r-reason">变更原因</label>
                <textarea id="r-reason" v-model="form.ruleReason" required maxlength="255" placeholder="说明这次规则调整的原因"></textarea>
              </div>
            </div>
            <p class="e-note">当前 {{ ruleVersionLabel(selectedSpace ?? spaces.find(s => s.id === dialog?.spaceId) ?? null) }}。已存在未来有效预约时，改变模式、缩容或调整座位会被拒绝；不改写历史预约快照。</p>
            <p class="e-quiet">整空间占用 1 份资源；按人数上限独立于物理容量；指定座位不从物理容量自动生成座位。</p>
          </template>

          <!-- 停用/启用场地 -->
          <template v-else-if="dialog.kind === 'toggle-space'">
            <h3>{{ selectedSpace?.name }}</h3>
            <p class="e-note">{{ selectedSpace ? toggleSpaceNote(selectedSpace) : '' }}</p>
            <p>目标状态：{{ selectedSpace?.status === 'ACTIVE' ? '停用' : '启用' }}</p>
          </template>

          <!-- 维护 / 不可售区间 -->
          <template v-else-if="dialog.kind === 'interval'">
            <div class="e-form">
              <div class="e-field"><label for="i-date">日期</label><input id="i-date" v-model="form.date" type="date" required></div>
              <div v-if="form.intervalKind === 'block'" class="e-field">
                <label for="i-type">不可售类型</label>
                <select id="i-type" v-model="form.blockType">
                  <option value="MANUAL_HOLD">人工保留</option>
                  <option value="MAINTENANCE">维护</option>
                  <option value="PRIVATE_EVENT">包场</option>
                  <option value="SETUP">布场撤场</option>
                  <option value="CLEANING">清洁</option>
                  <option value="CLOSURE">闭馆</option>
                </select>
              </div>
              <div class="e-field">
                <label for="i-start">开始时刻</label>
                <select id="i-start" v-model.number="form.startHour">
                  <option v-for="hour in hours" :key="hour" :value="hour">{{ pad(hour) }}:00</option>
                </select>
              </div>
              <div class="e-field">
                <label for="i-end">结束时刻</label>
                <select id="i-end" v-model.number="form.endHour">
                  <option v-for="hour in hours" :key="hour" :value="hour + 1">{{ pad(hour + 1) }}:00</option>
                </select>
              </div>
              <template v-if="form.intervalKind === 'block'">
                <div class="e-field"><label for="i-before">前置缓冲（分钟）</label><input id="i-before" v-model.number="form.bufferBefore" type="number" min="0" max="120" step="5"></div>
                <div class="e-field"><label for="i-after">后置缓冲（分钟）</label><input id="i-after" v-model.number="form.bufferAfter" type="number" min="0" max="120" step="5"></div>
              </template>
              <div class="e-field e-span">
                <label for="i-reason">{{ form.intervalKind === 'maintenance' ? '维护原因' : '业务说明' }}</label>
                <textarea id="i-reason" v-model="form.intervalReason" required maxlength="255" placeholder="填写可追溯的业务原因"></textarea>
              </div>
            </div>
            <p class="e-note">区间及缓冲与已有预约、维护或不可售发生冲突时，保存将被拒绝。现场报修不自动创建本条安排。</p>
          </template>

          <!-- 取消记录 -->
          <template v-else-if="dialog.kind === 'cancel-record'">
            <h3>{{ dialog.targetReason }}</h3>
            <p>{{ dialog.targetMeta }}</p>
            <div class="e-field">
              <label for="cancel-reason">取消原因</label>
              <textarea id="cancel-reason" v-model="form.cancelReason" required placeholder="填写取消原因"></textarea>
            </div>
            <p class="e-note">取消后重新读取可用性。取消维护不代表资产已完成修复，也不直接承诺场地恢复可售。</p>
          </template>

          <!-- 场馆信息 -->
          <template v-else-if="dialog.kind === 'venue-form'">
            <div class="e-form">
              <div class="e-field"><label for="v-name">场馆名称</label><input id="v-name" v-model="form.venueName" required maxlength="60"></div>
              <div class="e-field"><label for="v-area">所在区域</label><input id="v-area" v-model="form.area" required></div>
              <div class="e-field"><label for="v-address">详细地址</label><input id="v-address" v-model="form.address"></div>
              <div class="e-field"><label for="v-phone">服务电话或联系入口</label><input id="v-phone" v-model="form.phone"></div>
              <div class="e-field"><label for="v-open">常规开放时间</label><input id="v-open" v-model="form.venueOpen" type="time" required></div>
              <div class="e-field"><label for="v-close">常规关闭时间</label><input id="v-close" v-model="form.venueClose" type="time" required></div>
              <div class="e-field e-span"><label for="v-description">场馆说明</label><textarea id="v-description" v-model="form.venueDescription"></textarea></div>
            </div>
          </template>

          <!-- 营业安排 -->
          <template v-else-if="dialog.kind === 'calendar'">
            <div class="e-form">
              <div class="e-field">
                <label for="c-status">营业状态</label>
                <select id="c-status" v-model="form.calStatus" data-test="c-status">
                  <option value="OPEN">正常营业</option>
                  <option value="CLOSED">临时闭馆</option>
                  <option value="SPECIAL_HOURS">特殊时段</option>
                </select>
              </div>
              <div></div>
              <div class="e-field"><label for="c-open">开放时间</label><input id="c-open" v-model="form.calOpen" type="time" :required="form.calStatus !== 'CLOSED'"></div>
              <div class="e-field"><label for="c-close">关闭时间</label><input id="c-close" v-model="form.calClose" type="time" :required="form.calStatus !== 'CLOSED'"></div>
              <div class="e-field e-span">
                <label for="c-reason">业务原因</label>
                <textarea id="c-reason" v-model="form.calReason" required maxlength="255"></textarea>
              </div>
            </div>
            <p class="e-note">该安排作用于场馆当日所有场地。关闭或收紧时间与有效预约冲突时，服务端会拒绝保存。</p>
          </template>

          <p v-if="dialog.error" class="e-error" role="alert" data-test="dialog-error">{{ dialog.error }}</p>
        </div>
        <div class="e-dialog-foot">
          <button class="btn" type="button" @click="closeDialog">返回</button>
          <button class="btn primary" type="submit" :disabled="dialog.busy" :data-test="`confirm-${dialog.kind}`">
            {{ dialog.busy ? '处理中…' : dialog.submit }}
          </button>
        </div>
      </form>
    </dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useVenueAccessStore } from '@/stores/venueAccess'
import { useAppStore } from '@/stores/app'
import { inventoryAdminApi } from '@/api/inventory'
import { venueAdminApi, venueApi } from '@/api/venue'
import type {
  InventoryCalendarResponse,
  InventorySlot,
  VenueBusinessCalendar,
  VenueMaintenance,
  VenueSpace,
  VenueUnavailableBlock
} from '@/api/types'
import {
  ISOLATED_REQUEST as isolated,
  resourceCategoryLabel,
  slotStatusLabel} from '../adminShared'
import {
  businessStatusText,
  calendarHoursText,
  calendarReasonText,
  hoursAxis,
  mergeRecords,
  readableError,
  recordMetaLine,
  ruleModeLabel,
  ruleSubtitle,
  ruleUnchanged,
  ruleVersionLabel,
  seatDraftError,
  spaceStatusText,
  spaceRowCells,
  todayIso,
  toggleSpaceNote,
  venueHoursLabel,
  type SeatDraft
} from './resourcesShared'

type TabKey = 'inventory' | 'resources' | 'venue'
type SurfaceState = 'loading' | 'ready' | 'error' | 'forbidden'

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'inventory', label: '当日可用性' },
  { key: 'resources', label: '资源定义' },
  { key: 'venue', label: '场馆与营业安排' }
]

const RESOURCE_CATEGORY_OPTIONS: Record<string, string> = {
  SPORTS_FIELD: '体育场地',
  STUDY_ROOM: '自习空间',
  ACTIVITY_ROOM: '活动空间'
}

const BOOKING_MODE_OPTIONS: Record<string, string> = {
  EXCLUSIVE: '整空间',
  CAPACITY: '按人数',
  ASSIGNED_SEAT: '指定座位'
}

const route = useRoute()
// 面内权限统一取授权快照（B2-T34）：菜单树不含按钮级权限点（venue:calendar:manage 等），
// 快照由路由守卫 refreshAuthorization 刷新，旧正文同款口径。
const auth = useAuthStore()
const venueAccess = useVenueAccessStore()
const app = useAppStore()
const rootEl = ref<HTMLElement | null>(null)

const canInventory = computed(() => auth.hasPermission('venue:inventory:view'))
const canResources = computed(() => auth.hasPermission('venue:space:manage'))
const canCalendar = computed(() => auth.hasPermission('venue:calendar:manage'))
const canSystem = computed(() => auth.hasPermission('system:view'))

const tab = ref<TabKey>(route?.name === 'VenueAdminSpaces' ? 'resources' : 'inventory')
const surface = ref<SurfaceState>('loading')
const date = ref(todayIso())
const spaces = ref<VenueSpace[]>([])
const calendarsBySpace = ref(new Map<number, InventoryCalendarResponse>())
const venueCalendar = ref<VenueBusinessCalendar | null>(null)
const blocks = ref<VenueUnavailableBlock[]>([])
const maintenances = ref<VenueMaintenance[]>([])
const stale = ref(false)
const selectedSpaceId = ref<number | null>(null)
const selectedHour = ref(9)

const activeVenue = computed(() => venueAccess.venues.find((venue) => venue.id === venueAccess.activeVenueId) ?? null)
const venueName = computed(() => activeVenue.value?.name ?? '')
const hours = computed(() =>
  hoursAxis(activeVenue.value?.openTime, activeVenue.value?.closeTime, venueCalendar.value)
)

const selectedSpace = computed(() => spaces.value.find((space) => space.id === selectedSpaceId.value) ?? null)

const matrixRows = computed(() =>
  spaces.value.map((space) => ({
    space,
    cells: spaceRowCells(hours.value, calendarsBySpace.value.get(space.id)?.slots ?? [])
  }))
)

const inspectedSlot = computed<InventorySlot | null>(() => {
  if (!selectedSpaceId.value) return null
  const slots = calendarsBySpace.value.get(selectedSpaceId.value)?.slots ?? []
  return slots.find((slot) => hourOf(slot.startTime) === selectedHour.value) ?? null
})

const inspectedSlotReason = computed(() => {
  if (!inspectedSlot.value) return '当前日期的这个时段没有返回库存切片。'
  return inspectedSlot.value.reasonText?.trim() || '服务端未提供该时段的原因说明。'
})

const spaceNames = computed(() => new Map(spaces.value.map((space) => [space.id, space.name])))

const records = computed(() => mergeRecords(maintenances.value, blocks.value, spaceNames.value, date.value))

function hourOf(iso: string): number {
  const parsed = new Date(iso)
  return Number.isNaN(parsed.getTime()) ? -1 : parsed.getHours()
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

onMounted(async () => {
  if (!venueAccess.venues.length) {
    try {
      await venueAccess.refresh()
    } catch {
      // 场馆清单失败时由壳层锁定态提示；此处保持空态
    }
  }
  await bootstrap()
})

watch(
  () => [venueAccess.activeVenueId, venueAccess.scopeChangedAt],
  () => {
    void bootstrap()
  }
)

function switchTab(next: TabKey): void {
  tab.value = next
  void nextTick(() => {
    ;(rootEl.value?.querySelector(`#tab-${next}`) as HTMLElement | null)?.focus()
  })
}

function selectSpace(id: number): void {
  selectedSpaceId.value = id
}

function inspect(spaceId: number, hour: number): void {
  selectedSpaceId.value = spaceId
  selectedHour.value = hour
}

let bootstrapGeneration = 0
onBeforeUnmount(() => { bootstrapGeneration += 1 })

async function bootstrap(): Promise<void> {
  const generation = ++bootstrapGeneration
  const venueId = venueAccess.activeVenueId
  if (!canInventory.value && !canResources.value) {
    surface.value = 'forbidden'
    return
  }
  if (!venueId) {
    surface.value = venueAccess.venues.length ? 'error' : 'loading'
    return
  }
  surface.value = 'loading'
  try {
    const list = await venueApi.spaces(venueId, isolated)
    if (generation !== bootstrapGeneration || venueId !== venueAccess.activeVenueId) return
    spaces.value = list ?? []
    selectedSpaceId.value = list.some((space) => space.id === selectedSpaceId.value)
      ? selectedSpaceId.value
      : list[0]?.id ?? null
    await Promise.all([loadInventoryData(), loadMaintenances()])
    if (generation === bootstrapGeneration) surface.value = 'ready'
  } catch (error) {
    if (generation === bootstrapGeneration) surface.value = readableStatus(error)
  }
}

async function loadInventoryData(): Promise<void> {
  const venueId = venueAccess.activeVenueId
  if (!venueId || !canInventory.value || !spaces.value.length) return
  const pages = await Promise.allSettled(
    spaces.value.map((space) =>
      inventoryAdminApi.calendar({ venueId, spaceId: space.id, date: date.value }, isolated)
    )
  )
  const map = new Map<number, InventoryCalendarResponse>()
  pages.forEach((result, index) => {
    if (result.status === 'fulfilled') map.set(spaces.value[index].id, result.value)
  })
  calendarsBySpace.value = map
  venueCalendar.value = [...map.values()].find((item) => item.businessCalendar)?.businessCalendar ?? null
  try {
    blocks.value = await inventoryAdminApi.blocks({ venueId, date: date.value }, isolated)
  } catch {
    blocks.value = []
  }
}

async function loadMaintenances(): Promise<void> {
  const venueId = venueAccess.activeVenueId
  if (!venueId || !canResources.value || !spaces.value.length) {
    maintenances.value = []
    return
  }
  const pages = await Promise.allSettled(
    spaces.value.map((space) => venueAdminApi.maintenances(space.id, isolated))
  )
  const rows: VenueMaintenance[] = []
  pages.forEach((result) => {
    if (result.status === 'fulfilled') rows.push(...result.value)
  })
  maintenances.value = rows
}

function readableStatus(error: unknown): SurfaceState {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status === 403) return 'forbidden'
  return 'error'
}

async function refreshInventory(): Promise<void> {
  stale.value = false
  await Promise.all([loadInventoryData(), loadMaintenances()])
  app.showToast('当前日期的库存快照已重新读取')
}

async function onDateChange(): Promise<void> {
  if (!date.value) {
    date.value = todayIso()
    app.showToast('请选择运营日期')
    return
  }
  stale.value = false
  await loadInventoryData()
}

// ---------- 弹层 ----------

type DialogKind = 'space-form' | 'rule' | 'toggle-space' | 'interval' | 'cancel-record' | 'venue-form' | 'calendar'

interface DialogState {
  kind: DialogKind
  title: string
  submit: string
  error: string
  busy: boolean
  spaceId: number | null
  spaceMode: 'create' | 'edit'
  venueMode: 'create' | 'edit'
  targetKind: 'maintenance' | 'block' | null
  targetId: number | null
  targetReason: string
  targetMeta: string
}

const dialogEl = ref<HTMLDialogElement | null>(null)
const dialog = ref<DialogState | null>(null)
let lastTrigger: HTMLElement | null = null

const form = reactive({
  date: todayIso(),
  // 场地
  name: '',
  code: '',
  type: '',
  capacity: 1,
  price: 0,
  facility: '',
  description: '',
  // 规则
  category: 'SPORTS_FIELD',
  mode: 'EXCLUSIVE',
  capacityLimit: 1,
  ruleReason: '',
  // 区间
  intervalKind: 'maintenance' as 'maintenance' | 'block',
  blockType: 'MANUAL_HOLD',
  startHour: 9,
  endHour: 10,
  bufferBefore: 0,
  bufferAfter: 0,
  intervalReason: '',
  // 取消
  cancelReason: '',
  // 场馆
  venueName: '',
  area: '',
  address: '',
  phone: '',
  venueOpen: '08:00',
  venueClose: '22:00',
  venueDescription: '',
  // 日历
  calStatus: 'OPEN',
  calOpen: '08:00',
  calClose: '22:00',
  calReason: ''
})

const draftSeats = ref<SeatDraft[]>([])

function openDialog(next: DialogState, trigger: Event | null): void {
  const target = trigger?.target
  lastTrigger = target instanceof HTMLElement ? target : (document.activeElement as HTMLElement | null)
  dialog.value = next
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

function resetForm(): void {
  Object.assign(form, {
    date: todayIso(),
    name: '',
    code: '',
    type: '',
    capacity: 1,
    price: 0,
    facility: '',
    description: '',
    category: 'SPORTS_FIELD',
    mode: 'EXCLUSIVE',
    capacityLimit: 1,
    ruleReason: '',
    intervalKind: 'maintenance',
    blockType: 'MANUAL_HOLD',
    startHour: selectedHour.value,
    endHour: selectedHour.value + 1,
    bufferBefore: 0,
    bufferAfter: 0,
    intervalReason: '',
    cancelReason: '',
    venueName: '',
    area: '',
    address: '',
    phone: '',
    venueOpen: '08:00',
    venueClose: '22:00',
    venueDescription: '',
    calStatus: 'OPEN',
    calOpen: '08:00',
    calClose: '22:00',
    calReason: ''
  })
  draftSeats.value = []
}

function openSpaceForm(space: VenueSpace | null, event?: Event): void {
  resetForm()
  const editing = space != null
  if (editing) {
    form.name = space.name
    form.code = space.code ?? ''
    form.type = space.type ?? ''
    form.capacity = space.capacity ?? 1
    form.price = Number(space.pricePerHour ?? 0)
    form.facility = space.facilityText ?? ''
    form.description = space.description ?? ''
  }
  openDialog({
    kind: 'space-form',
    title: editing ? '编辑场地基础信息' : '新增场地',
    submit: '保存基础信息',
    error: '',
    busy: false,
    spaceId: space?.id ?? null,
    spaceMode: editing ? 'edit' : 'create',
    venueMode: 'edit',
    targetKind: null,
    targetId: null,
    targetReason: '',
    targetMeta: ''
  }, event ?? null)
}

function openRuleDialog(event?: Event): void {
  const space = selectedSpace.value
  if (!space) return
  resetForm()
  const rule = space.resourceRule
  form.category = rule?.resourceCategory ?? 'SPORTS_FIELD'
  form.mode = rule?.bookingMode ?? 'EXCLUSIVE'
  form.capacityLimit = rule?.capacityLimit ?? 1
  draftSeats.value = (rule?.seats ?? []).map((seat) => ({ seatCode: seat.seatCode, seatLabel: seat.seatLabel }))
  openDialog({
    kind: 'rule',
    title: `配置 ${space.name} 的预订规则`,
    submit: '保存规则新版本',
    error: '',
    busy: false,
    spaceId: space.id,
    spaceMode: 'edit',
    venueMode: 'edit',
    targetKind: null,
    targetId: null,
    targetReason: '',
    targetMeta: ''
  }, event ?? null)
}

function addSeat(): void {
  draftSeats.value.push({ seatCode: '', seatLabel: '' })
}

function removeSeat(index: number): void {
  draftSeats.value.splice(index, 1)
}

function openToggleSpace(event?: Event): void {
  const space = selectedSpace.value
  if (!space) return
  const off = space.status === 'ACTIVE'
  openDialog({
    kind: 'toggle-space',
    title: off ? '停用场地前核对影响' : '重新启用场地',
    submit: off ? '确认停用并处理受影响预约' : '确认启用',
    error: '',
    busy: false,
    spaceId: space.id,
    spaceMode: 'edit',
    venueMode: 'edit',
    targetKind: null,
    targetId: null,
    targetReason: '',
    targetMeta: ''
  }, event ?? null)
}

function openIntervalDialog(kind: 'maintenance' | 'block', event?: Event): void {
  const space = selectedSpace.value
  if (!space) return
  resetForm()
  form.intervalKind = kind
  form.startHour = selectedHour.value
  form.endHour = selectedHour.value + 1
  form.date = date.value
  openDialog({
    kind: 'interval',
    title: `${kind === 'maintenance' ? '安排维护' : '新增不可售'} · ${space.name}`,
    submit: kind === 'maintenance' ? '保存维护区间' : '保存不可售区间',
    error: '',
    busy: false,
    spaceId: space.id,
    spaceMode: 'edit',
    venueMode: 'edit',
    targetKind: kind,
    targetId: null,
    targetReason: '',
    targetMeta: ''
  }, event ?? null)
}

function openCancelRecord(row: { kind: 'maintenance' | 'block'; id: number; reason: string; startTime: string; endTime: string }, event?: Event): void {
  resetForm()
  openDialog({
    kind: 'cancel-record',
    title: row.kind === 'maintenance' ? '取消维护区间' : '取消不可售区间',
    submit: '确认取消区间',
    error: '',
    busy: false,
    spaceId: null,
    spaceMode: 'edit',
    venueMode: 'edit',
    targetKind: row.kind,
    targetId: row.id,
    targetReason: row.reason,
    targetMeta: `${recordPeriod(row.startTime, row.endTime)}`
  }, event ?? null)
}

function recordPeriod(start: string, end: string): string {
  const from = new Date(start)
  const to = new Date(end)
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) return ''
  return `${pad(from.getHours())}:${pad(from.getMinutes())}–${pad(to.getHours())}:${pad(to.getMinutes())}`
}

function openVenueForm(create: boolean, event?: Event): void {
  resetForm()
  if (!create && activeVenue.value) {
    form.venueName = activeVenue.value.name
    form.area = activeVenue.value.area ?? ''
    form.address = activeVenue.value.address ?? ''
    form.phone = activeVenue.value.phone ?? ''
    form.venueOpen = (activeVenue.value.openTime ?? '08:00').slice(0, 5)
    form.venueClose = (activeVenue.value.closeTime ?? '22:00').slice(0, 5)
    form.venueDescription = activeVenue.value.description ?? ''
  }
  openDialog({
    kind: 'venue-form',
    title: create ? '新增场馆' : '编辑场馆信息',
    submit: create ? '创建场馆' : '保存场馆信息',
    error: '',
    busy: false,
    spaceId: null,
    spaceMode: 'edit',
    venueMode: create ? 'create' : 'edit',
    targetKind: null,
    targetId: null,
    targetReason: '',
    targetMeta: ''
  }, event ?? null)
}

function openCalendarDialog(event?: Event): void {
  resetForm()
  form.date = date.value
  const calendar = venueCalendar.value
  form.calStatus = calendar?.businessStatus ?? 'OPEN'
  form.calOpen = (calendar?.openTime ?? activeVenue.value?.openTime ?? '08:00').slice(0, 5)
  form.calClose = (calendar?.closeTime ?? activeVenue.value?.closeTime ?? '22:00').slice(0, 5)
  form.calReason = calendar?.reasonText ?? ''
  openDialog({
    kind: 'calendar',
    title: `调整 ${date.value} 的营业安排`,
    submit: '保存当天营业安排',
    error: '',
    busy: false,
    spaceId: null,
    spaceMode: 'edit',
    venueMode: 'edit',
    targetKind: null,
    targetId: null,
    targetReason: '',
    targetMeta: ''
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
    state.error = readableError(error, '本次处理失败，未修改资源或库存。请保留填写内容并重试。')
    return
  }
  state.busy = false
  closeDialog()
}

function validate(state: DialogState): string | null {
  if (state.kind === 'interval' && form.endHour <= form.startHour) return '结束时刻必须晚于开始时刻。'
  if (state.kind === 'rule' && form.mode === 'ASSIGNED_SEAT') {
    const seatError = seatDraftError(draftSeats.value)
    if (seatError) return seatError
  }
  if (state.kind === 'venue-form' && form.venueClose <= form.venueOpen) return '关闭时间需晚于开放时间。'
  if (state.kind === 'calendar' && form.calStatus !== 'CLOSED' && form.calClose <= form.calOpen) {
    return '关闭时间必须晚于开放时间。'
  }
  return null
}

async function submitOf(state: DialogState): Promise<void> {
  const venueId = venueAccess.activeVenueId
  if (!venueId) throw new Error('尚未选择场馆')
  switch (state.kind) {
    case 'space-form': {
      const payload = {
        venueId,
        name: form.name.trim(),
        code: form.code.trim(),
        type: form.type.trim(),
        capacity: form.capacity,
        pricePerHour: form.price,
        facilityText: form.facility,
        description: form.description,
        status: 'ACTIVE'
      }
      if (state.spaceMode === 'create') {
        await venueAdminApi.createSpace(payload, isolated)
        app.showToast('场地基础信息已保存，请重新读取库存')
      } else if (state.spaceId != null) {
        await venueAdminApi.updateSpace(state.spaceId, payload, isolated)
        app.showToast('场地基础信息已保存，请重新读取库存')
      }
      stale.value = true
      await reloadSpaces()
      return
    }
    case 'rule': {
      const space = spaces.value.find((item) => item.id === state.spaceId)
      if (!space || state.spaceId == null) return
      const mode = form.mode as 'EXCLUSIVE' | 'CAPACITY' | 'ASSIGNED_SEAT'
      const seats = mode === 'ASSIGNED_SEAT'
        ? draftSeats.value.map((seat) => ({ seatCode: seat.seatCode.trim(), seatLabel: seat.seatLabel.trim() }))
        : []
      const capacityLimit = mode === 'CAPACITY' ? form.capacityLimit : undefined
      if (ruleUnchanged(space, { resourceCategory: form.category, bookingMode: mode, capacityLimit: capacityLimit ?? null, seats })) {
        throw new Error('本次配置与当前版本相同，无需创建新版本。')
      }
      await venueAdminApi.configureResourceRule(state.spaceId, {
        resourceCategory: form.category as never,
        bookingMode: mode,
        ...(capacityLimit != null ? { capacityLimit } : {}),
        seats,
        reasonText: form.ruleReason.trim()
      }, isolated)
      app.showToast('预订规则新版本已保存，库存待重新读取')
      stale.value = true
      await reloadSpaces()
      return
    }
    case 'toggle-space': {
      if (state.spaceId == null) return
      const off = selectedSpace.value?.status === 'ACTIVE'
      await venueAdminApi.updateSpaceStatus(state.spaceId, off ? 'MAINTENANCE' : 'ACTIVE', isolated)
      app.showToast(off ? '停用请求已处理' : '场地已启用')
      stale.value = true
      await Promise.all([reloadSpaces(), loadInventoryData()])
      return
    }
    case 'interval': {
      const spaceId = state.spaceId
      if (spaceId == null) return
      const startTime = `${form.date}T${pad(form.startHour)}:00:00`
      const endTime = `${form.date}T${pad(form.endHour)}:00:00`
      if (form.intervalKind === 'maintenance') {
        await venueAdminApi.createMaintenance(spaceId, { startTime, endTime, reason: form.intervalReason.trim() }, isolated)
        app.showToast('维护区间已登记，请重新读取库存')
      } else {
        await inventoryAdminApi.createBlock({
          venueId,
          spaceId,
          blockType: form.blockType,
          reasonCode: form.blockType,
          reasonText: form.intervalReason.trim(),
          startTime,
          endTime,
          bufferBeforeMinutes: form.bufferBefore,
          bufferAfterMinutes: form.bufferAfter
        })
        app.showToast('不可售区间已登记，请重新读取库存')
      }
      stale.value = true
      await loadInventoryData()
      return
    }
    case 'cancel-record': {
      if (state.targetKind === 'maintenance' && state.targetId != null) {
        await venueAdminApi.cancelMaintenance(state.targetId, isolated)
      } else if (state.targetKind === 'block' && state.targetId != null) {
        await inventoryAdminApi.cancelBlock(state.targetId, form.cancelReason.trim())
      } else return
      app.showToast('区间已取消，请重新读取库存')
      stale.value = true
      await loadInventoryData()
      return
    }
    case 'venue-form': {
      const payload = {
        name: form.venueName.trim(),
        area: form.area.trim(),
        address: form.address,
        phone: form.phone,
        openTime: form.venueOpen,
        closeTime: form.venueClose,
        description: form.venueDescription
      }
      if (state.venueMode === 'create') {
        const created = await venueAdminApi.createVenue(payload, isolated)
        await venueAccess.refresh()
        if (created?.id) venueAccess.switchVenue(created.id)
        app.showToast('新场馆已创建')
        await bootstrap()
      } else if (venueId) {
        await venueAdminApi.updateVenue(venueId, payload, isolated)
        await venueAccess.refresh()
        app.showToast('场馆信息已保存')
      }
      return
    }
    case 'calendar': {
      await inventoryAdminApi.saveCalendar({
        venueId,
        calendarDate: form.date || date.value,
        businessStatus: form.calStatus,
        ...(form.calStatus !== 'CLOSED' ? { openTime: form.calOpen, closeTime: form.calClose } : {}),
        reasonCode: form.calStatus,
        reasonText: form.calReason.trim()
      })
      app.showToast('当天营业安排已保存，请重新读取库存')
      stale.value = true
      await loadInventoryData()
      return
    }
  }
}

async function reloadSpaces(): Promise<void> {
  const venueId = venueAccess.activeVenueId
  if (!venueId) return
  try {
    const list = await venueApi.spaces(venueId, isolated)
    spaces.value = list ?? []
    if (!list.some((space) => space.id === selectedSpaceId.value)) {
      selectedSpaceId.value = list[0]?.id ?? null
    }
  } catch {
    // 保留先前清单；stale 横幅已提示重新读取
  }
}
</script>
