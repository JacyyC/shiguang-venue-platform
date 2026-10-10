import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import View from './ShiguangVenueListView.vue'
import { useShiguangStore } from '@/stores/shiguang'
const api = vi.hoisted(() => ({ list: vi.fn(), summary: vi.fn(), track: vi.fn() }))
vi.mock('@/api/venue', () => ({ venueApi: { list: api.list } }))
vi.mock('@/api/reservation', () => ({ reservationApi: { availabilitySummary: api.summary } }))
vi.mock('@/stores/funnel', () => ({ useFunnelStore: () => ({ track: api.track }) }))
const deferred = <T,>() => { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
const page = (id = 1, total = 1) => ({ records: [{ id, name: `场馆${id}`, spaceTypes: ['BASKETBALL'] }], total })
const mountView = () => mount(View, { global: { stubs: { ShiguangArt: true }, config: { errorHandler: () => {} } } })
const button = (w: ReturnType<typeof mountView>, text: string) => w.findAll('button').find(b => b.text().includes(text))!
describe('venue list request contract', () => {
 beforeEach(() => { setActivePinia(createPinia()); vi.resetAllMocks(); api.list.mockResolvedValue(page()); api.summary.mockResolvedValue([]); api.track.mockResolvedValue(undefined) })
 it('distinguishes initial loading from successful empty and unknown count', async () => {
  const pending = deferred<any>(); api.list.mockReturnValue(pending.promise); const w=mountView()
  expect(w.text()).toContain('正在读取'); expect(w.text()).not.toContain('0 处场馆'); expect(w.text()).not.toContain('暂时没有合适')
  pending.resolve({records:[],total:0}); await flushPromises();expect(w.text()).toContain('暂时没有合适');w.unmount()
 })
 it.each([429,500,0,401,403])('contains failure %s and retries first page', async status => {
  api.list.mockRejectedValueOnce({response:{status}});const w=mountView();await flushPromises();expect(w.find('[role="alert"]').exists()).toBe(true);expect(w.text()).not.toContain('暂时没有合适')
  await button(w,'重新加载').trigger('click');await flushPromises();expect(w.text()).toContain('场馆1');expect(api.list.mock.calls.map(c=>c[0].pageNum)).toEqual([1,1]);expect(api.list.mock.calls[0][1]?.skipGlobalErrorRedirect).not.toBe(true);w.unmount()
 })
 it('retains successful content, retries the failed page and rejects double paging', async () => {
  api.list.mockResolvedValueOnce(page(1,3)).mockRejectedValueOnce(new Error('failure')).mockResolvedValueOnce(page(2,3));const w=mountView();await flushPromises()
  await button(w,'加载更多').trigger('click');await flushPromises();expect(w.text()).toContain('场馆1');await button(w,'重试这一页').trigger('click');await flushPromises();expect(api.list.mock.calls.map(c=>c[0].pageNum)).toEqual([1,2,2]);expect(w.text()).toContain('场馆2')
  const pending=deferred<any>();api.list.mockReturnValueOnce(pending.promise);await button(w,'加载更多').trigger('click');await button(w,'正在加载').trigger('click');expect(api.list.mock.calls.map(c=>c[0].pageNum)).toEqual([1,2,2,3]);pending.resolve(page(3,3));await flushPromises();w.unmount()
 })
 it('queries new keyword/area from page one and ignores old responses', async () => {
  const old=deferred<any>();api.list.mockReturnValueOnce(old.promise).mockResolvedValueOnce(page(2));const w=mountView();useShiguangStore().query='新';useShiguangStore().area='东区';await flushPromises()
  expect(api.list.mock.calls.at(-1)?.[0]).toMatchObject({pageNum:1,keyword:'新',area:'东区'});old.resolve(page(1));await flushPromises();expect(w.text()).toContain('场馆2');expect(w.text()).not.toContain('场馆1');expect(api.track).toHaveBeenCalledTimes(1);w.unmount()
 })
 it('optional summary/analytics failures cannot fail the list', async () => {
  api.summary.mockRejectedValue(new Error('optional'));api.track.mockRejectedValue(new Error('optional'));const w=mountView();await flushPromises();expect(w.text()).toContain('场馆1');expect(w.find('[role="alert"]').exists()).toBe(false);w.unmount()
 })
 it('new date summary wins over late old date summary', async () => {
  const old=deferred<any>();api.summary.mockReturnValueOnce(old.promise).mockResolvedValueOnce([{venueId:1,available:true,startTime:'2099-01-02T16:00:00'}]);const w=mountView();await flushPromises();useShiguangStore().day=1;await flushPromises();old.resolve([{venueId:1,available:true,startTime:'2099-01-01T10:00:00'}]);await flushPromises();expect(w.text()).toContain('16:00');expect(w.text()).not.toContain('10:00');w.unmount()
 })
 it('unmounted request cannot emit analytics', async () => {
  const pending=deferred<any>();api.list.mockReturnValue(pending.promise);const w=mountView();w.unmount();pending.resolve(page());await flushPromises();expect(api.track).not.toHaveBeenCalled()
 })
})
