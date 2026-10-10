import http, { type HttpRequestConfig, type PageResult } from './http'
import type {
  CouponTemplateResponse,
  PriceRuleResponse,
  PromotionCampaignResponse,
  PromotionOverviewResponse,
  SaveCouponTemplateRequest,
  SavePriceRuleRequest,
  SavePromotionCampaignRequest,
  UserCouponResponse
} from './types'

type QueryParams = Record<string, unknown>

const statusConfig = (status: string) => ({ params: { status } })

export const promotionAdminApi = {
  campaigns: (params: QueryParams, config?: HttpRequestConfig) =>
    http.get<PageResult<PromotionCampaignResponse>>('/admin/promotions/campaigns', { params, ...config }),
  createCampaign: (payload: SavePromotionCampaignRequest, config?: HttpRequestConfig) =>
    http.post<PromotionCampaignResponse>('/admin/promotions/campaigns', payload, config),
  updateCampaign: (id: number, payload: SavePromotionCampaignRequest, config?: HttpRequestConfig) =>
    http.put<PromotionCampaignResponse>(`/admin/promotions/campaigns/${id}`, payload, config),
  updateCampaignStatus: (id: number, status: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/promotions/campaigns/${id}/status`, undefined, { ...statusConfig(status), ...config }),
  /**
   * 券模板列表。`status` 为服务端新增的可选过滤（B4-P9）：缺省不过滤，
   * 显式传值时按模板状态（DRAFT/SCHEDULED/ACTIVE/PAUSED/ENDED）精确过滤，非法值 400。
   *
   * `config` 供跨域只读调用方（如会员权益目录）传入 `ISOLATED_REQUEST`：该端点要求
   * `coupon:manage`，只持有 `member-reward:catalog:manage` 的运营者会拿到 403，
   * 若不跳过全局 403 跳转就会被整体踢出当前页面（B4-P9/M4）。
   */
  couponTemplates: (params: QueryParams, config?: HttpRequestConfig) =>
    http.get<PageResult<CouponTemplateResponse>>('/admin/promotions/coupon-templates', { params, ...config }),
  createCouponTemplate: (payload: SaveCouponTemplateRequest, config?: HttpRequestConfig) =>
    http.post<CouponTemplateResponse>('/admin/promotions/coupon-templates', payload, config),
  updateCouponTemplate: (id: number, payload: SaveCouponTemplateRequest, config?: HttpRequestConfig) =>
    http.put<CouponTemplateResponse>(`/admin/promotions/coupon-templates/${id}`, payload, config),
  updateCouponTemplateStatus: (id: number, status: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/promotions/coupon-templates/${id}/status`, undefined, { ...statusConfig(status), ...config }),
  couponRedemptions: (params: QueryParams, config?: HttpRequestConfig) =>
    http.get<PageResult<UserCouponResponse>>('/admin/promotions/coupon-redemptions', { params, ...config }),
  priceRules: (params: QueryParams, config?: HttpRequestConfig) =>
    http.get<PageResult<PriceRuleResponse>>('/admin/promotions/price-rules', { params, ...config }),
  createPriceRule: (payload: SavePriceRuleRequest, config?: HttpRequestConfig) =>
    http.post<PriceRuleResponse>('/admin/promotions/price-rules', payload, config),
  updatePriceRule: (id: number, payload: SavePriceRuleRequest, config?: HttpRequestConfig) =>
    http.put<PriceRuleResponse>(`/admin/promotions/price-rules/${id}`, payload, config),
  updatePriceRuleStatus: (id: number, status: string, config?: HttpRequestConfig) =>
    http.put<void>(`/admin/promotions/price-rules/${id}/status`, undefined, { ...statusConfig(status), ...config }),
  overview: (config?: HttpRequestConfig) => http.get<PromotionOverviewResponse>('/admin/promotions/overview', config)
}

export const couponApi = {
  claimable: (params: QueryParams = {}, config?: HttpRequestConfig) => http.get<CouponTemplateResponse[]>('/coupons/claimable', { params, ...config }),
  my: (params: QueryParams, config?: HttpRequestConfig) => http.get<PageResult<UserCouponResponse>>('/coupons/my', { params, ...config }),
  claim: (templateId: number, config?: HttpRequestConfig) => http.post<UserCouponResponse>(`/coupons/${templateId}/claim`, undefined, config)
}
