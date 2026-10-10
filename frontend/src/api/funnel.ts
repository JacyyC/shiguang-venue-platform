import http, { type HttpRequestConfig } from './http'
import type {
  FunnelEventPayload,
  FunnelEventResponse,
  FunnelFavoriteResponse,
  FunnelOverviewQueryParams,
  FunnelOverviewResponse
} from './types'

const backgroundTrackingConfig = { skipGlobalErrorToast: true }

export const funnelApi = {
  record: (payload: FunnelEventPayload) =>
    http.post<FunnelEventResponse>('/funnel/events', payload, backgroundTrackingConfig),
  myFavorites: (config?: HttpRequestConfig) =>
    http.get<FunnelFavoriteResponse[]>('/funnel/favorites/my', config),
  favoriteVenue: (venueId: number, context: Partial<FunnelEventPayload> = {}, config?: HttpRequestConfig) =>
    http.post<FunnelFavoriteResponse>(`/funnel/favorites/venues/${venueId}`, context, config),
  cancelFavorite: (venueId: number, context: Partial<FunnelEventPayload> = {}, config?: HttpRequestConfig) =>
    http.delete<void>(`/funnel/favorites/venues/${venueId}`, { data: context, ...config }),
  favoriteStatus: (venueId: number) =>
    http.get<{ favorite: boolean }>(`/funnel/favorites/venues/${venueId}/status`)
}

export const funnelAdminApi = {
  /**
   * 管理端总览。四个筛选参数由 S03 补齐访问器后才真正被 `@ModelAttribute` 绑定（B4-F16），
   * 因此 `startDate/endDate/venueId/sourceChannel` 必须原样透传；缺省时由服务端回落
   * 「近 7 天 + 可管理场馆集合」。`config` 供面内传 `ISOLATED_REQUEST`（403/失败在面内承接）。
   */
  overview: (params: FunnelOverviewQueryParams = {}, config?: HttpRequestConfig) =>
    http.get<FunnelOverviewResponse>('/admin/funnel/overview', { params, ...config }),
  /**
   * 独立样本端点（与总览共用同一 QueryWindow/Scope/渠道过滤，服务端上限 20 条）。
   * 样本弹层打开时按同一条件快照调用它（B4-F7：不留无调用方端点），失败只在弹层内显示错误；
   * 总览内联的 `sampleEvents` 仍用于「查看 N 条样本」按钮上的条数。
   */
  samples: (params: FunnelOverviewQueryParams = {}, config?: HttpRequestConfig) =>
    http.get<FunnelEventResponse[]>('/admin/funnel/samples', { params, ...config })
}
