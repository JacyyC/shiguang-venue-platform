import http, { type HttpRequestConfig, type PageResult } from './http'
import type {
  MemberAccountResponse,
  MemberAccountSummary,
  MemberLevelResponse,
  MemberOverviewResponse,
  MemberRewardCatalogResponse,
  MemberRewardEventResponse,
  MemberRewardRedeemRequest,
  MemberRewardRedemptionResponse,
  PublishMemberRewardVersionRequest,
  SaveMemberLevelRequest,
  SaveMemberRewardCatalogItemRequest
} from './types'

/**
 * 会员域管理端接口。
 *
 * 全部方法接受可选 `config`：第四批会员面在面内承接 403/404/409（`ISOLATED_REQUEST`），
 * 不能让全局错误拦截器把运营者整页跳走到 `/403` 而丢失正在核对的内容
 * （SHIGUANG-BATCH-04-REFACTOR-20260914 S04，contracts.md §0 与 §7）。
 */
export const adminMemberApi = {
  levels: (config?: HttpRequestConfig) => http.get<MemberLevelResponse[]>('/admin/members/levels', config),
  createLevel: (payload: SaveMemberLevelRequest, config?: HttpRequestConfig) =>
    http.post<MemberLevelResponse>('/admin/members/levels', payload, config),
  updateLevel: (id: number, payload: SaveMemberLevelRequest, config?: HttpRequestConfig) =>
    http.put<MemberLevelResponse>(`/admin/members/levels/${id}`, payload, config),
  updateLevelStatus: (id: number, status: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/members/levels/${id}/status`, { status }, config),
  accounts: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<MemberAccountSummary>>('/admin/members/accounts', { params, ...config }),
  account: (userId: number, config?: HttpRequestConfig) =>
    http.get<MemberAccountResponse>(`/admin/members/accounts/${userId}`, config),
  overview: (config?: HttpRequestConfig) => http.get<MemberOverviewResponse>('/admin/members/overview', config)
}

export const memberApi = {
  me: (config?: HttpRequestConfig) => http.get<MemberAccountResponse>('/members/me', config)
}

export const memberRewardApi = {
  catalog: (config?: HttpRequestConfig) => http.get<MemberRewardCatalogResponse[]>('/member-rewards/catalog', config),
  preview: (itemId: number, config?: HttpRequestConfig) => http.get<MemberRewardCatalogResponse>(`/member-rewards/catalog/${itemId}/preview`, config),
  redeem: (itemId: number, payload: MemberRewardRedeemRequest, config?: HttpRequestConfig) =>
    http.post<MemberRewardRedemptionResponse>(`/member-rewards/catalog/${itemId}/redemptions`, payload, config),
  history: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<MemberRewardRedemptionResponse>>('/member-rewards/redemptions', { params, ...config })
}

export const adminMemberRewardApi = {
  createCatalogItem: (payload: SaveMemberRewardCatalogItemRequest, config?: HttpRequestConfig) =>
    http.post<MemberRewardCatalogResponse>('/member-rewards/admin/catalog', payload, config),
  listCatalogItems: (config?: HttpRequestConfig) =>
    http.get<MemberRewardCatalogResponse[]>('/member-rewards/admin/catalog', config),
  updateCatalogItemStatus: (itemId: number, status: string, config?: HttpRequestConfig) =>
    http.put<MemberRewardCatalogResponse>(`/member-rewards/admin/catalog/${itemId}/status`, { status }, config),
  publishVersion: (itemId: number, payload: PublishMemberRewardVersionRequest, config?: HttpRequestConfig) =>
    http.post<MemberRewardCatalogResponse>(`/member-rewards/admin/catalog/${itemId}/versions`, payload, config),
  /**
   * 异常兑换列表。`status` 为服务端新增的可选筛选（B4-M1）：缺省仍返回
   * PENDING/FAILED/REJECTED 三态；显式传值可查 SUCCEEDED（补偿的唯一合法前置）。
   */
  exceptions: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<MemberRewardRedemptionResponse>>('/member-rewards/admin/exceptions', { params, ...config }),
  retry: (id: number, config?: HttpRequestConfig) =>
    http.post<MemberRewardRedemptionResponse>(`/member-rewards/admin/redemptions/${id}/retry`, undefined, config),
  compensate: (id: number, reason: string, config?: HttpRequestConfig) =>
    http.post<MemberRewardRedemptionResponse>(`/member-rewards/admin/redemptions/${id}/compensate`, { reason }, config),
  events: (params: Record<string, unknown>, config?: HttpRequestConfig) =>
    http.get<PageResult<MemberRewardEventResponse>>('/member-rewards/admin/events', { params, ...config })
}
