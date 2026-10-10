import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import HomeView from './HomeView.vue'
import { venueApi } from '@/api/venue'
import { reservationApi } from '@/api/reservation'
import { activityApi } from '@/api/activity'
import { couponApi } from '@/api/promotion'
import { placementApi } from '@/api/placement'
import { useMenuStore } from '@/stores/menu'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/venue', () => ({
  venueApi: { list: vi.fn() }
}))
vi.mock('@/api/reservation', () => ({
  reservationApi: { my: vi.fn() }
}))
vi.mock('@/api/activity', () => ({
  activityApi: { activities: vi.fn(), companions: vi.fn() }
}))
vi.mock('@/api/promotion', () => ({
  couponApi: { my: vi.fn() }
}))

const passthrough = { template: '<div><slot /></div>' }
const buttonStub = { template: '<button type="button"><slot /></button>' }

const placementApiMock = vi.hoisted(() => ({
  homePrimaryDecision: vi.fn().mockResolvedValue(null),
  recordEvent: vi.fn().mockResolvedValue({ resultStatus: 'ACCEPTED', eventType: 'CLICK' })
}))

const advertisingApiMock = vi.hoisted(() => ({
  homeCommercialDecision: vi.fn().mockResolvedValue(null),
  recordEvent: vi.fn().mockResolvedValue({ resultStatus: 'ACCEPTED', eventType: 'CLICK', replayed: false })
}))

vi.mock('@/api/placement', () => ({ placementApi: placementApiMock }))
vi.mock('@/api/advertising', () => ({ advertisingApi: advertisingApiMock }))

const stubs = {
  ElButton: buttonStub,
  ElIcon: passthrough,
  ElInput: { template: '<input v-model="modelValue" />', props: ['modelValue'] },
  ElSkeleton: true,
  // V3 S04：空态引导改由 EmptyState 承载，桩渲染标题/描述/动作槽以支撑断言
  EmptyState: { props: ['title', 'description', 'compact'], template: '<section><strong>{{ title }}</strong><span>{{ description }}</span><slot name="action" /></section>' }
}

const emptyCursor = { records: [], nextCursor: null, hasMore: false }

const futureDateTime = (offsetMs: number) => {
  const date = new Date(Date.now() + offsetMs)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 本自然周（周一起）内的确定时刻：周 start + 1h，避免边界抖动 */
const inWeekDateTime = () => {
  const now = new Date()
  const weekStart = new Date(now.getFullYear(), now.getMonth(), now.getDate() - ((now.getDay() + 6) % 7)).getTime()
  return futureDateTime(weekStart + 3600 * 1000 - Date.now())
}

const activity = (overrides: Partial<Record<string, unknown>> & { id: number; title: string }) => ({
  activityNo: 'A-1',
  groupId: 1,
  organizerUserId: 1,
  activityType: 'STUDY',
  location: { name: '活动室' },
  capacityTotal: 20,
  participantCount: 3,
  status: 'ONGOING',
  startTime: inWeekDateTime(),
  endTime: futureDateTime(2 * 3600 * 1000),
  ...overrides
})

function createMenuRouter(perms: string[]) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const menu = useMenuStore()
  menu.$patch({
    loaded: true,
    tokenSnapshot: 'token',
    menus: perms.map((perm, index) => ({
      id: index + 1,
      parentId: 0,
      name: `菜单${index + 1}`,
      path: '/',
      component: '',
      perms: perm,
      type: 'MENU'
    }))
  })
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: HomeView },
      { path: '/venues', component: { template: '<div />' } },
      { path: '/venues/:id', component: { template: '<div />' } },
      { path: '/me/reservations', component: { template: '<div />' } },
      { path: '/me/orders/:id/pay', component: { template: '<div />' } },
      { path: '/me/orders', component: { template: '<div />' } },
      { path: '/community', component: { template: '<div />' } },
      { path: '/activities', component: { template: '<div />' } },
      { path: '/activities/:id', component: { template: '<div />' } },
      { path: '/companion-requests/:id', component: { template: '<div />' } }
    ]
  })
  return { pinia, router }
}

async function mountHome(pinia: ReturnType<typeof createPinia>, router: ReturnType<typeof createRouter>) {
  const wrapper = mount(HomeView, { global: { plugins: [pinia, router], stubs } })
  await flushPromises()
  return wrapper
}

const venue = (overrides: Partial<Record<string, unknown>> & { id: number; name: string }) => ({
  address: '测试地址 1 号',
  area: '测试区',
  status: 'ACTIVE',
  openTime: '08:00:00',
  closeTime: '22:00:00',
  minPrice: 45,
  activeSpaceCount: 2,
  spaceTypes: ['BADMINTON'],
  ...overrides
})

describe('HomeView 海报式动线（R-04）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(venueApi.list).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 6 })
    vi.mocked(reservationApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 8 })
    vi.mocked(activityApi.activities).mockResolvedValue(emptyCursor)
    vi.mocked(activityApi.companions).mockResolvedValue(emptyCursor)
    vi.mocked(couponApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 100 })
  })

  it('renders the V4.3 brand-narrative hero: kicker, display headline with accent word, kinds and the V5 live panel', async () => {
    vi.mocked(venueApi.list).mockResolvedValueOnce({
      records: [
        venue({ id: 11, name: '羽跃羽毛球馆' }),
        venue({ id: 12, name: '蔚蓝游泳中心', spaceTypes: ['SWIMMING'] }),
        venue({ id: 13, name: '拾光自习室·春熙店', spaceTypes: ['STUDY_ROOM'] })
      ] as never,
      total: 3,
      pageNum: 1,
      pageSize: 100
    })

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    // V4.3 大字：无衬线特大号「时间」+ 第二层「自有去处」（去处为主色 accent，全首屏唯一彩色词）
    const title = wrapper.get('[data-test="home-hero-title"]')
    expect(title.get('.t1').text()).toBe('时间')
    expect(title.get('.t2').text()).toBe('自有去处')
    expect(title.get('.t2 em').text()).toBe('去处')
    // 零装饰：无 4:3 大图、无示意角标、无书法/印章/旋转
    expect(wrapper.find('.hero-art').exists()).toBe(false)
    expect(wrapper.find('.hero-cap').exists()).toBe(false)
    // 四场景入口带真实计数（球馆 1 / 泳池 1 / 自习 1 / 活动 0）
    const kinds = wrapper.findAll('.hp-kind')
    expect(kinds).toHaveLength(4)
    expect(kinds[0].text()).toContain('球馆')
    expect(kinds[0].text()).toContain('1 家')
    expect(kinds[1].text()).toContain('泳池')
    expect(kinds[2].text()).toContain('自习')
    // V5 今日实况数据栏：数字归栏（可约 6 / 开放场馆 3），无权限与未登录项显示「—」且不可点
    const live = wrapper.get('[data-test="home-live-panel"]')
    const nums = live.findAll('.n')
    expect(nums).toHaveLength(4)
    expect(nums[0].text()).toBe('6')
    expect(nums[0].classes()).toContain('hot')
    expect(nums[1].text()).toBe('3')
    expect(nums[2].text()).toBe('—')
    expect(nums[3].text()).toBe('—')
    expect(live.text()).toContain('今天可约场地')
    expect(live.text()).toContain('开放场馆')
    expect(live.text()).toContain('本周社区活动')
    expect(live.text()).toContain('可用优惠券')
    // 有数行可点入口：「—」行（无权限/未登录）纯展示
    const liveRows = live.findAll('.li')
    expect(liveRows[0].attributes('href')).toBe('/venues')
    expect(liveRows[1].attributes('href')).toBe('/venues')
    expect((liveRows[2].element as HTMLElement).tagName).toBe('DIV')
    expect((liveRows[3].element as HTMLElement).tagName).toBe('DIV')
    // footline 口径合并：只留搜索胶囊并左对齐，不再重复实时数字
    expect(wrapper.find('[data-test="home-live-count"]').exists()).toBe(false)
    expect(wrapper.find('.hp-footline .hp-search input').exists()).toBe(true)
    // 搜索退为首屏底部次要胶囊
    expect(wrapper.findAll('.rv').length).toBeGreaterThanOrEqual(4)

    wrapper.unmount()
  })

  it('shows the next trip as an editorial band with countdown and credential action', async () => {
    vi.mocked(reservationApi.my).mockResolvedValueOnce({
      records: [
        {
          id: 101,
          userId: 1,
          venueId: 11,
          spaceId: 3,
          venueName: '滨江网球中心',
          startTime: futureDateTime(2 * 3600 * 1000),
          endTime: futureDateTime(4 * 3600 * 1000),
          status: 'PAID',
          amount: 296,
          payableAmount: 296
        },
        {
          id: 102,
          userId: 1,
          venueId: 12,
          spaceId: 1,
          venueName: '云谷游泳馆',
          startTime: futureDateTime(26 * 3600 * 1000),
          endTime: futureDateTime(27 * 3600 * 1000),
          status: 'PENDING_PAYMENT',
          amount: 68
        }
      ],
      total: 2,
      pageNum: 1,
      pageSize: 8
    })

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'community:access', 'reservation:my'])
    const auth = useAuthStore(pinia)
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' } })
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    const band = wrapper.get('[data-test="home-next-band"]')
    expect(band.find('.hp-next .t').classes()).toContain('sg-num')
    expect(band.text()).toContain('滨江网球中心')
    expect(band.text()).toMatch(/下一场/)
    expect(band.text()).toMatch(/倒计时 \d{2}:\d{2}:\d{2}/)
    expect(band.text()).toContain('已支付')
    const primary = band.get('[data-test="next-primary-action"]')
    expect(primary.text()).toBe('出示核销凭证')
    expect(primary.attributes('href')).toBe('/me/reservations')

    wrapper.unmount()
  })

  it('routes an unpaid next trip to payment instead of the credential', async () => {
    vi.mocked(reservationApi.my).mockResolvedValueOnce({
      records: [
        {
          id: 202,
          userId: 1,
          venueId: 12,
          spaceId: 1,
          venueName: '云谷游泳馆',
          orderId: 9001,
          startTime: futureDateTime(3600 * 1000),
          endTime: futureDateTime(2 * 3600 * 1000),
          status: 'PENDING_PAYMENT',
          amount: 68
        }
      ],
      total: 1,
      pageNum: 1,
      pageSize: 8
    })

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'reservation:my'])
    const auth = useAuthStore(pinia)
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' } })
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)
    const primary = wrapper.get('[data-test="next-primary-action"]')
    expect(primary.text()).toBe('去支付')
    expect(primary.attributes('href')).toBe('/me/orders/9001/pay')

    wrapper.unmount()
  })

  it('fills the V5 live panel with real week-activity and usable-coupon counts', async () => {
    vi.mocked(activityApi.activities).mockResolvedValueOnce({
      records: [
        activity({ id: 1, title: '周末自习局' }),
        activity({
          id: 2,
          title: '下周活动',
          startTime: futureDateTime(9 * 86400 * 1000),
          endTime: futureDateTime(9 * 86400 * 1000 + 3600 * 1000)
        })
      ] as never,
      nextCursor: null,
      hasMore: false
    })
    vi.mocked(couponApi.my).mockResolvedValueOnce({
      records: [
        { id: 1, templateId: 1, status: 'CLAIMED' },
        { id: 2, templateId: 2, status: 'USED' },
        { id: 3, templateId: 3, status: 'RELEASED' }
      ] as never,
      total: 3,
      pageNum: 1,
      pageSize: 100
    })

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'community:access', 'reservation:my'])
    const auth = useAuthStore(pinia)
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' } })
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    const nums = wrapper.get('[data-test="home-live-panel"]').findAll('.n')
    expect(nums[0].text()).toBe('0')
    expect(nums[2].text()).toBe('1')
    expect(nums[3].text()).toBe('2')
    // 券计数与优惠券页「去使用」口径一致：仅 CLAIMED/RELEASED 计入；四行全为可点入口
    const liveRows = wrapper.get('[data-test="home-live-panel"]').findAll('.li')
    expect(liveRows[2].attributes('href')).toBe('/activities')
    expect(liveRows[3].attributes('href')).toBe('/me/coupons')

    wrapper.unmount()
  })

  it('merges the two empty-state guide cards into a single text-link line', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'community:access', 'reservation:my'])
    const auth = useAuthStore(pinia)
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' } })
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    expect(wrapper.find('[data-test="home-next-band"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="home-trip-guide"]').length).toBe(0)
    expect(wrapper.findAll('[data-test="home-happening-guide"]').length).toBe(0)
    const guide = wrapper.get('[data-test="home-empty-guide"]')
    expect(guide.text()).toContain('还没有行程')
    expect(guide.get('a').attributes('href')).toBe('/venues')

    wrapper.unmount()
  })

  it('lays the recommendations out as an App-Store rail of poster cards with count head (V3 S04)', async () => {
    vi.mocked(venueApi.list).mockResolvedValueOnce({
      records: [
        venue({ id: 11, name: '羽跃羽毛球馆' }),
        venue({ id: 12, name: '蔚蓝游泳中心', spaceTypes: ['SWIMMING'] }),
        venue({ id: 13, name: '拾光自习室·春熙店', spaceTypes: ['STUDY_ROOM'] })
      ] as never,
      total: 3,
      pageNum: 1,
      pageSize: 6
    })

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    expect(wrapper.find('.hp-band-head').text()).toContain('附近的场馆')
    expect(wrapper.find('.hp-band-head').text()).toContain('3 家开放')
    const cards = wrapper.findAll('.hp-rail .hp-card')
    expect(cards).toHaveLength(3)
    expect(cards[0].text()).toContain('羽跃羽毛球馆')
    expect(cards[1].text()).toContain('蔚蓝游泳中心')
    expect(cards[2].text()).toContain('拾光自习室·春熙店')
    // 海报缩版：无实拍图的卡按馆型注入 wash（松绿=羽毛球 / 雾蓝=游泳 / 蜜金=自习）
    expect(cards[0].get('.hp-th').attributes('data-wash')).toBe('pine')
    expect(cards[1].get('.hp-th').attributes('data-wash')).toBe('mist')
    expect(cards[2].get('.hp-th').attributes('data-wash')).toBe('honey')
    expect(cards[0].text()).toContain('¥45/时')
    // 轨道挂 .sg-rail（右缘露下一张、隐藏滚动条）且整枚状态章不再出现
    expect(wrapper.find('.hp-rail').classes()).toContain('sg-rail')
    expect(wrapper.find('.hp-rail .sg-stamp').exists()).toBe(false)
    expect(wrapper.find('.hp-rail .sg-tag').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps the explicit venue failure state with retry', async () => {
    vi.mocked(venueApi.list).mockRejectedValueOnce(new Error('venue down'))

    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    const failure = wrapper.find('.home-state')
    expect(failure.exists()).toBe(true)
    expect(failure.attributes('role')).toBe('alert')
    expect(failure.text()).toContain('推荐场馆暂时没有加载出来')

    wrapper.unmount()
  })

  it('routes the hero search to the venue list with the keyword', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)

    const input = wrapper.find('.hp-search input')
    await input.setValue('网球场')
    await wrapper.find('.hp-search').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/venues')
    expect(router.currentRoute.value.query.keyword).toBe('网球场')

    wrapper.unmount()
  })
})

describe('HomeView 广告位落位（R-05）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(venueApi.list).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 6 })
    vi.mocked(reservationApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 8 })
    vi.mocked(activityApi.activities).mockResolvedValue(emptyCursor)
    vi.mocked(activityApi.companions).mockResolvedValue(emptyCursor)
    vi.mocked(couponApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 100 })
  })

  it('keeps host order: hero → next band → primary placement → happening → commercial → venue rail (V4 §03)', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()

    const wrapper = await mountHome(pinia, router)
    const html = wrapper.html()

    // 两个广告组件文件零改动；商业位仍在首屏之后、场馆轨道之前，顺序与 R-05 一致
    const slots = wrapper.findAll('.ad-slot')
    expect(slots).toHaveLength(2)
    const firstSlot = html.indexOf('ad-slot')
    const railIndex = html.indexOf('venue-rail-section')
    const heroIndex = html.indexOf('hp-hero')
    expect(firstSlot).toBeGreaterThan(heroIndex)
    expect(railIndex).toBeGreaterThan(firstSlot)

    wrapper.unmount()
  })
})

describe('HomeView V6-UI-UX-SHIGUANG S01：hero B 居中 + 实况横条 + 时钟彩蛋 + 进场分层', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(venueApi.list).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 6 })
    vi.mocked(reservationApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 8 })
    vi.mocked(activityApi.activities).mockResolvedValue(emptyCursor)
    vi.mocked(activityApi.companions).mockResolvedValue(emptyCursor)
    vi.mocked(couponApi.my).mockResolvedValue({ records: [], total: 0, pageNum: 1, pageSize: 100 })
  })

  it('hero B 居中版式（预览 §02 1:1）：hero-main 单列、t1-row 同轴、egg 彩蛋、散开入口类', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view'])
    await router.push('/')
    await router.isReady()
    const wrapper = await mountHome(pinia, router)

    // 中轴海报式：hp-hero-main 限宽容器包住 kicker/大字/引语；kicker 在 hero-main 内（V11 A3 全同步带柿橙括号）
    const hero = wrapper.get('.hp-hero')
    expect(hero.get('.hp-hero-main .hp-kicker').text()).toBe('「场地空间与活动平台」')
    expect(hero.get('.hp-hero-main .hp-hero-quote').text()).toBe('「把下一段时间，先留给自己。」')
    expect(hero.get('.hp-hero-main .hp-hero-note').element).toBeTruthy()

    // t1-row：大字与时钟彩蛋同行基线居中；t1 展示字栈（V6 定稿换展示字，V4 的 sans 大字退役）
    const t1 = hero.get('.t1')
    expect(t1.text()).toBe('时间')
    expect(t1.attributes('title')).toBe('点我，散开成粒子')
    // 测试环境无 AmbientBackground 画布 → 控制器不介入直达成品页：can-scatter 就绪、渐变未亮
    expect(t1.classes()).toContain('can-scatter')
    expect(t1.classes()).not.toContain('grad-on')
    expect(hero.find('.t1-row .egg').element).toBeTruthy()

    // 时钟彩蛋：HH:MM:SS 结构（秒数 b 段）+「今日可约 N 个场地」（真实计数）
    const egg = hero.get('.egg')
    expect(egg.classes()).not.toContain('on')
    expect(egg.get('.clk b').text()).toMatch(/^:\d{2}$/)
    expect(egg.get('.egg-lb').text()).toContain('今日可约')

    // t2 字距收半口径 + em 去处主色
    expect(hero.get('.t2').text()).toBe('自有去处')
    expect(hero.get('.t2 em').text()).toBe('去处')

    // footline 挂 sg-intro-el，进场 150ms 位浮现
    const footline = hero.get('.hp-footline')
    expect(footline.classes()).toContain('sg-intro-el')
    expect(footline.attributes('style')).toContain('--sg-irvd: 150ms')

    // hero 五段/实况/四格挂 ps-block（聚形交接对象）
    expect(hero.get('.hp-hero-main').classes()).toContain('ps-block')
    expect(hero.get('.hp-live').classes()).toContain('ps-block')
    expect(hero.get('.hp-kinds').classes()).toContain('ps-block')
    // 场景四格为 hero 区块级直接子元素（V6 §04 2a：通栏，不再嵌左栏）
    expect(hero.get('.hp-kinds').element.parentElement).toBe(hero.element)

    wrapper.unmount()
  })

  it('附近场馆轨道随量变（预览 §04 2b）：(100%−28px)/3 铺满 + ≤900 半宽 + ≤560 78%', async () => {
    const source = readFileSync(resolve(process.cwd(), 'src/views/user/HomeView.vue'), 'utf8')
    expect(source).toMatch(/\/\* V6 预览 §04 修复 2b[\s\S]*?flex: 0 0 calc\(\(100% - 28px\) \/ 3\);\s*\n\s*min-width: 250px;/)
    expect(source).toMatch(/@media \(max-width: 900px\)\s*\{\s*\.hp-card\s*\{[^}]*flex-basis: calc\(\(100% - 14px\) \/ 2\);/)
    expect(source).toMatch(/@media \(max-width: 560px\)\s*\{\s*\.hp-card\s*\{[^}]*flex-basis: 78%;/)
  })

  it('实况横条（预览 §03 1:1）：四格标签/可点入口结构与 V5 口径一致，仅版式转横排', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'community:access', 'reservation:my'])
    await router.push('/')
    await router.isReady()
    const wrapper = await mountHome(pinia, router)
    const live = wrapper.get('[data-test="home-live-panel"]')
    const rows = live.findAll('.li')
    expect(rows).toHaveLength(4)
    expect(live.text()).toContain('今天可约场地')
    expect(live.text()).toContain('开放场馆')
    expect(live.text()).toContain('本周社区活动')
    expect(live.text()).toContain('可用优惠券')
    wrapper.unmount()
  })

  it('进场分层节奏（预览 §01 --rvd 家族重排）：各区块挂 --sg-irvd 同族延迟', async () => {
    const { pinia, router } = createMenuRouter(['user:view', 'venue:view', 'community:access', 'reservation:my'])
    await router.push('/')
    await router.isReady()
    const auth = useAuthStore(pinia)
    auth.$patch({ token: 'token', user: { id: 1, username: 'user', nickname: '林同学', status: 'ACTIVE' } })
    const wrapper = await mountHome(pinia, router)

    // 空态引导卡占位下一场/此刻位（同 420ms 族位）；广告/轨道按 540/760/860 分层
    const emptyGuide = wrapper.get('[data-test="home-empty-guide"]')
    expect(emptyGuide.attributes('style')).toContain('--sg-irvd: 420ms')
    const adSlots = wrapper.findAll('.ad-slot')
    expect(adSlots[0].attributes('style')).toContain('--sg-irvd: 540ms')
    expect(adSlots[1].attributes('style')).toContain('--sg-irvd: 760ms')
    const rail = wrapper.get('.venue-rail-section')
    expect(rail.attributes('style')).toContain('--sg-irvd: 860ms')

    wrapper.unmount()
  })
})
