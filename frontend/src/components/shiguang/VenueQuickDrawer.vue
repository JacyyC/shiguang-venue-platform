<template>
  <dialog
    ref="dialogRef"
    class="sgv-detail-dialog detail-dialog"
    :aria-labelledby="'sgv-quick-title'"
    @close="onClose"
  >
    <div id="sgv-quick-content">
      <div class="dialog-header">
        <span class="eyebrow">{{ kindLabel }}</span>
        <button class="icon-button" type="button" aria-label="关闭场馆速览" @click="close">
          <svg aria-hidden="true"><use href="#sgv-close" /></svg>
        </button>
      </div>
      <template v-if="venue">
        <div class="detail-art" aria-hidden="true"><ShiguangArt :scene="scene" /></div>
        <div class="detail-main">
          <p class="eyebrow">{{ typeLabel }}</p>
          <h2 id="sgv-quick-title">{{ venue.name }}</h2>
          <p class="detail-lead">给这件喜欢的事，留个位置。</p>
          <p class="detail-location"><svg aria-hidden="true"><use href="#sgv-pin" /></svg>{{ venue.area }}{{ venue.address ? ' · ' + venue.address : '' }}</p>
          <p class="detail-price">¥{{ minPrice ?? '—' }}<small>/ 小时起</small></p>
          <p class="detail-description">{{ venue.description }}</p>
          <h3 class="detail-label">选择日期 <small>时段随当前时间滚动展示</small></h3>
          <div class="detail-days">
            <button
              v-for="offset in [0, 1]"
              :key="offset"
              type="button"
              :class="{ 'is-active': dayOffset === offset }"
              :aria-pressed="dayOffset === offset ? 'true' : 'false'"
              @click="setDay(offset)"
            >{{ dayLabel(offset) }}</button>
          </div>
          <h3 class="detail-label">选择时段 <small>{{ slotHint }}</small></h3>
          <div v-if="slotsLoading" class="local-warning">正在查询可约时段…</div>
          <div v-else-if="slotsError" class="local-warning" role="alert">{{ slotsError }}，可在详情页重试。</div>
          <div v-else-if="visibleSlots.length" class="slot-options">
            <button
              v-for="slot in visibleSlots"
              :key="slot.startTime"
              type="button"
              class="slot-button"
              :class="{ 'is-active': selectedSlot?.startTime === slot.startTime }"
              :disabled="slot.status !== 'AVAILABLE'"
              :aria-pressed="selectedSlot?.startTime === slot.startTime ? 'true' : 'false'"
              @click="selectedSlot = slot"
            >
              <span>{{ slotRange(slot) }}</span>
              <small>{{ slot.status === 'AVAILABLE' ? `¥${slot.price} · 可加入` : '已被安排' }}</small>
            </button>
          </div>
          <div v-else class="local-warning">这个日期暂无可约时段，换一天或看完整排期。</div>
          <p class="local-warning">以上时段概览来自「{{ previewSpaceName || '首个在售场地' }}」；其他场地、人数与座位请在详情页选择。</p>
          <div class="detail-total">
            <span>时段价格（优惠以确认页为准）</span>
            <strong>¥{{ selectedSlot ? selectedSlot.price : (venue.minPrice ?? '—') }}</strong>
          </div>
          <button class="primary-button" type="button" @click="goDetail">
            <span>查看完整详情</span>
            <svg aria-hidden="true"><use href="#sgv-arrow" /></svg>
          </button>
          <p class="local-warning">完整排期、场地切换与预约在详情页继续。</p>
        </div>
      </template>
      <div v-else class="detail-main"><p class="local-warning">正在载入场馆信息…</p></div>
    </div>
  </dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { venueApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import type { VenueSpace, AvailabilitySlot, VenueListItem } from '@/api/types'
import ShiguangArt from '@/components/shiguang/ShiguangArt.vue'
import { mapVenueScene } from '@/utils/shiguangScene'

const props = defineProps<{ venueId: number | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const router = useRouter()
const dialogRef = ref<HTMLDialogElement | null>(null)
const venue = ref<VenueListItem | null>(null)
const spaces = ref<VenueSpace[]>([])
const slots = ref<AvailabilitySlot[]>([])
const slotsLoading = ref(false)
const slotsError = ref('')
const previewSpaceName = ref('')
const dayOffset = ref(0)
const selectedSlot = ref<AvailabilitySlot | null>(null)

const KIND_LABELS: Record<string, string> = { BADMINTON: '球馆', BASKETBALL: '球馆', SWIM: '泳馆', MEETING_ROOM: '共学空间', LOUNGE: '城市客厅' }
const TYPE_LABELS: Record<string, string> = { BADMINTON: '羽毛球 · 运动', BASKETBALL: '篮球 · 运动', SWIM: '游泳 · 换一种呼吸', MEETING_ROOM: '共学 · 一个人也很好', LOUNGE: '相聚 · 见面正当时' }

const primaryType = computed(() => spaces.value.find((space) => space.type)?.type ?? '')
const minPrice = computed(() => spaces.value.length ? Math.min(...spaces.value.map((space) => space.pricePerHour)) : null)
const scene = computed(() => mapVenueScene(spaces.value.map((space) => space.type)))
const kindLabel = computed(() => KIND_LABELS[primaryType.value] ?? '场馆速览')
const typeLabel = computed(() => TYPE_LABELS[primaryType.value] ?? '场馆 · 速览')

const dateText = (offset: number) => {
  const date = new Date()
  date.setDate(date.getDate() + offset)
  const text = `${String(date.getMonth() + 1).padStart(2, '0')}.${String(date.getDate()).padStart(2, '0')}`
  return `${offset === 0 ? '今天' : '明天'} ${text}`
}

const dayLabel = (offset: number) => dateText(offset)

const apiDate = (offset: number) => {
  const date = new Date()
  date.setDate(date.getDate() + offset)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const SLOT_LIMIT = 4

/** 裁决：时段只展示所选日期内当前时间之后的若干档（避免整日铺开），保持预览的两列网格密度。 */
const visibleSlots = computed(() => {
  let list = slots.value
  if (dayOffset.value === 0) {
    const now = new Date()
    list = list.filter((slot) => new Date(slot.startTime) > now)
  }
  const bookable = list.filter((slot) => slot.status === 'AVAILABLE')
  const rest = list.filter((slot) => slot.status !== 'AVAILABLE')
  return [...bookable, ...rest].slice(0, SLOT_LIMIT)
})

const slotHint = computed(() => {
  if (slotsLoading.value) return '查询中'
  if (slotsError.value) return '暂时读不到'
  if (!visibleSlots.value.length) return '该日期暂无可约'
  return selectedSlot.value ? '当前可加入' : '选择一档时间'
})

const slotRange = (slot: AvailabilitySlot) => {
  const start = new Date(slot.startTime)
  const end = new Date(slot.endTime)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return slot.label
  const fmt = (date: Date) => `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  return `${fmt(start)}–${fmt(end)}`
}

const setDay = (offset: number) => {
  dayOffset.value = offset
  selectedSlot.value = null
  loadSlots()
}

const loadSlots = async () => {
  const target = venue.value
  if (!target) return
  slotsLoading.value = true
  slotsError.value = ''
  selectedSlot.value = null
  try {
    const space = spaces.value[0]
    previewSpaceName.value = space?.name || ''
    slots.value = space ? await reservationApi.availability({ spaceId: space.id, date: apiDate(dayOffset.value) }) : []
  } catch {
    slots.value = []
    slotsError.value = '时段暂时无法加载，不影响查看完整详情'
  } finally {
    slotsLoading.value = false
  }
}

const loadVenue = async (id: number) => {
  venue.value = null
  spaces.value = []
  slots.value = []
  try {
    const [detail, spaceList] = await Promise.all([
      venueApi.detail(id),
      venueApi.spaces(id).catch(() => [] as VenueSpace[])
    ])
    venue.value = detail as unknown as VenueListItem
    spaces.value = spaceList.filter((space) => space.status === 'ACTIVE')
    await loadSlots()
  } catch {
    venue.value = null
  }
}

watch(() => props.venueId, (id) => {
  if (id == null) {
    dialogRef.value?.open && dialogRef.value.close()
    return
  }
  dialogRef.value?.showModal()
  loadVenue(id)
})

const close = () => {
  dialogRef.value?.close()
}

const onClose = () => {
  emit('close')
}

const goDetail = () => {
  const id = props.venueId
  close()
  if (id != null) {
    router.push({ path: `/venues/${id}`, query: { date: apiDate(dayOffset.value) } })
  }
}
</script>
