export interface UserProfile {
  id: number
  username: string
  nickname?: string
  phone?: string
  email?: string
  avatar?: string
  status: string
}

export interface MenuNode {
  id: number
  parentId: number
  name: string
  path: string
  component: string
  icon?: string
  perms?: string
  type: string
  children?: MenuNode[]
}

export interface Venue {
  id: number
  name: string
  address: string
  area: string
  phone?: string
  openTime?: string
  closeTime?: string
  description?: string
  image?: string
  status: string
}

export interface VenueListItem extends Venue {
  averageRating?: number
  reviewCount?: number
  minPrice?: number
  activeSpaceCount?: number
  spaceTypes?: string[]
}

export interface VenueSpace {
  id: number
  venueId: number
  name: string
  type: string
  code?: string
  capacity?: number
  pricePerHour: number
  facilityText?: string
  description?: string
  status: string
  resourceRule?: ResourceRule
}

export type ResourceCategory = 'SPORTS_FIELD' | 'STUDY_ROOM' | 'ACTIVITY_ROOM'
export type BookingMode = 'EXCLUSIVE' | 'CAPACITY' | 'ASSIGNED_SEAT'

export interface ResourceSeat {
  id: number
  seatCode: string
  seatLabel: string
  sortOrder?: number
  status: string
  available?: boolean
}

export interface ResourceRule {
  id?: number
  spaceId: number
  resourceCategory: ResourceCategory
  bookingMode: BookingMode
  /** 资源规则的可售单位：EXCLUSIVE 恒为 1（一笔整场占用），CAPACITY 为可售人数上限。 */
  capacityLimit?: number
  /**
   * 场地物理承载人数（`venue_space.capacity`），与 `capacityLimit` 分属两层、不自动同步。
   * F-01（SHIGUANG-WAVE-01-REMEDIATION-20260916 S04）起由服务端返回，供提交前提示人数上限。
   */
  physicalCapacity?: number
  versionNo: number
  status: string
  effectiveFrom?: string
  seats: ResourceSeat[]
}

export interface ResourceRuleChangeRequest {
  resourceCategory: ResourceCategory
  bookingMode: BookingMode
  capacityLimit?: number
  seats: Array<{ seatCode: string; seatLabel: string }>
  reasonText: string
}

export interface ResourceAvailabilityResponse {
  spaceId: number
  rule: ResourceRule
  participantCount: number
  remainingCapacity: number
  bookable: boolean
  reasonCode?: string
  reasonText?: string
}

export interface VenueMaintenance {
  id: number
  facilityId?: number
  spaceId: number
  startTime: string
  endTime: string
  reason: string
  status: string
}

export interface AvailabilitySlot {
  spaceId: number
  startTime: string
  endTime: string
  status: string
  label: string
  price: number
  reason?: string
  inventoryStatus?: string
  reasonCode?: string
  blockType?: string
  blockId?: number
  reservationId?: number
}

/** 场馆级可约聚合（GET /reservations/availability-summary）：available=false 表示当天暂无可约时段。 */
export interface VenueAvailabilitySummary {
  venueId: number
  available: boolean
  spaceId?: number | null
  startTime?: string | null
  endTime?: string | null
  price?: number | null
  availableSlotCount: number
}

export interface InventorySlot {
  venueId: number
  spaceId: number
  startTime: string
  endTime: string
  status: string
  label: string
  price?: number
  reasonCode?: string
  reasonText?: string
  blockType?: string
  blockId?: number
  reservationId?: number
  reservationStatus?: string
  sourceType?: string
  relatedEntityType?: string
  relatedEntityId?: number
}

export interface InventorySummary {
  venueId?: number
  spaceId?: number
  date?: string
  availableSlots: number
  reservedSlots: number
  unavailableSlots: number
  manualHoldSlots: number
  closedSlots: number
  bufferSlots: number
  availableMinutes?: number
  reservedMinutes?: number
  unavailableMinutes?: number
  manualHoldMinutes?: number
  closedMinutes?: number
  bufferMinutes?: number
}

export interface InventoryCalendarResponse {
  slots: InventorySlot[]
  summary: InventorySummary
  businessCalendar?: VenueBusinessCalendar | null
}

export interface VenueBusinessCalendar {
  id: number
  venueId: number
  calendarDate: string
  openTime?: string
  closeTime?: string
  businessStatus: string
  reasonCode?: string
  reasonText?: string
  sourceType?: string
}

export interface SaveBusinessCalendarRequest {
  venueId: number
  calendarDate: string
  openTime?: string
  closeTime?: string
  businessStatus: string
  reasonCode?: string
  reasonText?: string
}

export interface VenueUnavailableBlock {
  id: number
  venueId: number
  spaceId?: number
  blockType: string
  reasonCode: string
  reasonText: string
  startTime: string
  endTime: string
  bufferBeforeMinutes: number
  bufferAfterMinutes: number
  sourceType?: string
  relatedEntityType?: string
  relatedEntityId?: number
  status: string
  createTime?: string
  updateTime?: string
}

export interface SaveUnavailableBlockRequest {
  venueId: number
  spaceId?: number
  blockType: string
  reasonCode: string
  reasonText: string
  startTime: string
  endTime: string
  bufferBeforeMinutes?: number
  bufferAfterMinutes?: number
}

export interface AssetItem {
  id: number
  assetNo?: string
  venueId?: number
  spaceId?: number
  assetType?: string
  name?: string
  brand?: string
  model?: string
  serialNo?: string
  supplierName?: string
  purchaseDate?: string
  purchaseAmount?: number
  warrantyStartDate?: string
  warrantyEndDate?: string
  responsibleUserId?: number
  status?: string
  currentUnavailableBlockId?: number
  metadataJson?: string
  createTime?: string
  updateTime?: string
}

export interface AssetInspectionRecord {
  id: number
  inspectionNo?: string
  assetId?: number
  venueId?: number
  spaceId?: number
  siteTaskId?: number
  inspectorUserId?: number
  inspectionResult?: string
  riskLevel?: string
  checklistJson?: string
  issueSummary?: string
  nextInspectionDueAt?: string
  inspectedAt?: string
  metadataJson?: string
  createTime?: string
  updateTime?: string
}

export interface AssetRepairRecord {
  id: number
  repairNo?: string
  assetId?: number
  venueId?: number
  spaceId?: number
  siteTaskId?: number
  status?: string
  requestedBy?: number
  repairOwnerId?: number
  supplierName?: string
  failureCode?: string
  failureDescription?: string
  estimatedCostAmount?: number
  actualCostAmount?: number
  downtimeStart?: string
  downtimeEnd?: string
  unavailableBlockId?: number
  resolution?: string
  sourceType?: string
  metadataJson?: string
  createTime?: string
  updateTime?: string
}

export interface AssetLifecycleEvent {
  id: number
  eventNo?: string
  assetId?: number
  venueId?: number
  spaceId?: number
  eventType?: string
  fromStatus?: string
  toStatus?: string
  operatorUserId?: number
  sourceType?: string
  reasonCode?: string
  reasonText?: string
  costAmount?: number
  downtimeStart?: string
  downtimeEnd?: string
  siteTaskId?: number
  repairRecordId?: number
  inspectionRecordId?: number
  unavailableBlockId?: number
  metadataJson?: string
  occurredAt?: string
  createTime?: string
}

export interface Reservation {
  id: number
  userId: number
  venueId: number
  spaceId: number
  startTime: string
  endTime: string
  status: string
  contactName?: string
  contactPhone?: string
  remark?: string
  rejectReason?: string
  amount: number
  originalAmount?: number
  memberDiscountAmount?: number
  couponDiscountAmount?: number
  activityDiscountAmount?: number
  payableAmount?: number
  paidAmount?: number
  feeAmount?: number
  netAmount?: number
  pricingSnapshotJson?: string
  promotionCampaignId?: number
  priceRuleId?: number
  couponTemplateId?: number
  userCouponId?: number
  couponLockToken?: string
  payExpireTime?: string
  resourceCategorySnapshot?: ResourceCategory
  bookingModeSnapshot?: BookingMode
  ruleIdSnapshot?: number
  ruleVersionSnapshot?: number
  participantCount?: number
  allocationSnapshotJson?: string
  venueName?: string
  orderId?: number
}

export interface ReservationRescheduleRequest {
  id: number
  reservationId: number
  userId: number
  venueId: number
  spaceId: number
  fromStartTime: string
  fromEndTime: string
  toStartTime: string
  toEndTime: string
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED'
  reasonCode?: string
  reasonText?: string
  reviewedAt?: string
  reviewReason?: string
  createTime?: string
  updateTime?: string
}

export interface ReservationRules {
  maxSlotsPerBooking: number
  advanceDays?: number
  freeCancelHours: number
  lockSeconds: number
}

/**
 * 一次提交意图的只读核对结果。
 * NONE=该键从未产生结果（可安全形成新意图）；PROCESSING=原请求仍在处理中（结果待核对）；
 * COMPLETED=已产生预约。
 */
export interface ReservationSubmissionResult {
  status: 'NONE' | 'PROCESSING' | 'COMPLETED'
  reservationId?: number | null
  reservation?: Reservation | null
}

export interface MemberLevelResponse { id: number; levelCode: string; levelName: string; growthThreshold: number; discountRate: number; benefitText?: string; ruleVersion?: string; status: string }
export interface SaveMemberLevelRequest {
  levelCode: string
  levelName: string
  growthThreshold: number
  discountRate: number
  benefitText?: string
  /** 规则版本。服务端在缺失时回落 'B2_MEMBER_V1' 并覆盖库中已有值，因此编辑必须回传（B4-M6）。 */
  ruleVersion?: string
  status?: string
}
export interface MemberAccountSummary { userId: number; username?: string; nickname?: string; levelCode?: string; levelName?: string; growthValue: number; pointsBalance?: number; totalConsumptionAmount?: number; totalReservations?: number; totalMemberDiscountAmount?: number; lastActiveTime?: string; profileTags?: string[] }
export interface MemberAccountResponse extends MemberAccountSummary { memberAccountId?: number; discountRate?: number; benefitText?: string; nextLevel?: MemberLevelResponse | null; nextLevelGrowthGap?: number }
export interface MemberOverviewResponse { totalMembers: number; activeMembers: number; levelDistribution: Record<string, number>; coreUsers: MemberAccountSummary[]; dormantUsers: MemberAccountSummary[] }

export interface MemberRewardCatalogResponse {
  itemId: number
  itemCode: string
  itemName: string
  descriptionText?: string
  status: string
  versionId?: number
  versionNo?: number
  pointsCost?: number
  minimumLevelCode?: string
  minimumLevelName?: string
  minimumGrowthThreshold?: number
  couponTemplateId?: number
  validityDays?: number
  effectiveStartTime?: string
  effectiveEndTime?: string
  availablePoints?: number
  pendingOffsetPoints?: number
  eligible: boolean
  unavailableReason?: string
}

export interface MemberRewardRedemptionResponse {
  id: number
  redemptionNo: string
  itemCode: string
  itemName: string
  versionNo?: number
  pointsCost?: number
  status: string
  failureCode?: string
  failureMessage?: string
  userCouponId?: number
  completedAt?: string
  compensatedAt?: string
  createTime?: string
}

export interface MemberRewardEventResponse {
  id: number
  eventType: string
  redemptionId?: number
  catalogItemId?: number
  catalogVersionId?: number
  actorUserId?: number
  sourceType?: string
  reasonCode?: string
  reasonText?: string
  fromStatus?: string
  toStatus?: string
  resultStatus?: string
  occurredAt?: string
}

export interface MemberRewardRedeemRequest {
  catalogVersionId: number
  idempotencyKey: string
}

export interface SaveMemberRewardCatalogItemRequest {
  itemCode: string
  itemName: string
  descriptionText?: string
}

export interface PublishMemberRewardVersionRequest {
  pointsCost: number
  minimumLevelCode: string
  couponTemplateId: number
  validityDays?: number
  effectiveStartTime?: string
  effectiveEndTime?: string
}
export interface PricingPreviewResponse {
  originalAmount: number
  memberDiscountAmount: number
  couponDiscountAmount: number
  activityDiscountAmount: number
  payableAmount: number
  memberLevel?: MemberLevelResponse | null
  memberAccount?: MemberAccountResponse | null
  promotionCampaignId?: number
  priceRuleId?: number
  couponTemplateId?: number
  userCouponId?: number
  pricingVersion?: string
  couponUnavailableReason?: string
  pricingSnapshotJson?: string
}

export interface FunnelEventPayload {
  sessionKey: string
  visitorId?: string
  eventName: string
  eventStage: string
  sourceChannel?: string
  campaignId?: string
  pagePath?: string
  referrerPath?: string
  keyword?: string
  venueId?: number
  spaceId?: number
  reservationId?: number
  orderId?: number
  userCouponId?: number
  payableAmount?: number
  resultStatus?: string
  dropoffReasonCode?: string
  reasonText?: string
  idempotencyKey?: string
  metadata?: Record<string, unknown>
}

export interface FunnelEventResponse {
  id: number
  eventName: string
  eventStage: string
  resultStatus?: string
  venueId?: number
  occurredAt?: string
}

export interface FunnelFavoriteResponse {
  id: number
  userId?: number
  venueId: number
  sourceChannel?: string
  status: string
  createTime?: string
  updateTime?: string
}

export interface FunnelOverviewQueryParams {
  startDate?: string
  endDate?: string
  venueId?: number
  sourceChannel?: string
}

/** F-22：步骤计量状态。NOT_APPLICABLE 表示该步骤在当前查询口径下没有归属事实，不是零转化。 */
export type FunnelStepMeasurement = 'MEASURED' | 'NOT_APPLICABLE'

export interface FunnelStepMetric {
  eventName: string
  label: string
  measurement?: FunnelStepMeasurement
  /** 当前口径的第一个可计量步骤；界面据此显示起点，不给起点一个虚假转化率。 */
  startStep?: boolean
  notApplicableReason?: string
  count: number | null
  conversionRate: number | null
}

export interface FunnelDropoffMetric {
  reasonCode: string
  label: string
  count: number
}

export interface FunnelSourceMetric {
  sourceChannel: string
  count: number
}

export interface FunnelOverviewResponse {
  stepMetrics: FunnelStepMetric[]
  dropoffReasons: FunnelDropoffMetric[]
  sourceChannels: FunnelSourceMetric[]
  topKeywords: string[]
  dataGaps: string[]
  sampleEvents: FunnelEventResponse[]
}

export interface PromotionCampaignResponse {
  id: number
  campaignCode: string
  campaignName: string
  campaignType: string
  venueId?: number
  spaceId?: number
  targetMemberLevel?: string
  startTime: string
  endTime: string
  budgetAmount: number
  discountType: string
  discountValue: number
  maxDiscountAmount?: number
  priority: number
  allowCouponStack: boolean
  status: string
}

export interface SavePromotionCampaignRequest {
  campaignCode: string
  campaignName: string
  campaignType?: string
  venueId?: number
  spaceId?: number
  targetMemberLevel?: string
  startTime: string
  endTime: string
  budgetAmount?: number
  discountType: string
  discountValue: number
  maxDiscountAmount?: number
  priority?: number
  allowCouponStack?: boolean
  status?: string
}

export interface CouponTemplateResponse {
  id: number
  templateCode: string
  templateName: string
  couponType: string
  discountValue: number
  thresholdAmount: number
  maxDiscountAmount?: number
  totalQuantity: number
  issuedQuantity: number
  usedQuantity: number
  perUserLimit: number
  validStartTime: string
  validEndTime: string
  campaignId?: number
  venueId?: number
  spaceId?: number
  allowActivityStack: boolean
  status: string
  /** 成本承担方。S03 补的服务端只读出参（B4-P5）；自由文本，前端按原值展示。 */
  costOwner?: string
}

export interface SaveCouponTemplateRequest {
  templateCode: string
  templateName: string
  couponType: string
  discountValue: number
  thresholdAmount?: number
  maxDiscountAmount?: number
  totalQuantity?: number
  perUserLimit?: number
  validStartTime: string
  validEndTime: string
  campaignId?: number
  venueId?: number
  spaceId?: number
  allowActivityStack?: boolean
  costOwner?: string
  status?: string
}

export interface UserCouponResponse {
  id: number
  templateId: number
  templateCode?: string
  templateName?: string
  couponType?: string
  discountValue?: number
  thresholdAmount?: number
  status: string
  claimTime?: string
  validStartTime?: string
  validEndTime?: string
  reservationId?: number
  orderId?: number
  discountAmount?: number
  sourceType?: string
  releasedAt?: string
}

export interface PriceRuleResponse {
  id: number
  ruleCode: string
  ruleName: string
  ruleType: string
  venueId?: number
  spaceId?: number
  memberLevelCode?: string
  startTime: string
  endTime: string
  dayOfWeekMask?: string
  startClock?: string
  endClock?: string
  adjustmentType: string
  adjustmentValue: number
  priority: number
  status: string
  reasonText?: string
}

export interface SavePriceRuleRequest {
  ruleCode: string
  ruleName: string
  ruleType: string
  venueId?: number
  spaceId?: number
  memberLevelCode?: string
  startTime: string
  endTime: string
  dayOfWeekMask?: string
  startClock?: string
  endClock?: string
  adjustmentType: string
  adjustmentValue: number
  priority?: number
  status?: string
  reasonText?: string
}

export interface PromotionOverviewResponse {
  activeCampaigns: number
  activeCouponTemplates: number
  activePriceRules: number
  claimedCoupons: number
  lockedCoupons: number
  usedCoupons: number
  refundedCoupons: number
  totalCouponDiscountAmount: number
}

export interface LifecycleEventResponse {
  eventType: string
  fromStatus?: string
  toStatus?: string
  sourceType: string
  reasonCode?: string
  reasonText?: string
  createTime: string
}

export interface ReservationLifecycleResponse {
  reservationId: number
  orderId?: number
  venueId: number
  venueName?: string
  spaceId: number
  credentialStatus: string
  checkinCodeLast4?: string
  checkinCode?: string
  qrPayload?: string
  checkinWindowStart?: string
  checkinWindowEnd?: string
  attendanceStatus: string
  checkinStatus: string
  noShowStatus: string
  checkedInAt?: string
  lateMinutes?: number
  reasonCode?: string
  reasonText?: string
  events?: LifecycleEventResponse[]
}

export interface CheckinVerifyRequest {
  credential: string
  deviceRef?: string
  reasonCode?: string
  reasonText?: string
}

export interface OrderRefundSummary {
  refundId: number
  refundNo: string
  refundAmount: number
  status: string
  userVisibleReason: string
}

export interface Order {
  id: number
  orderNo: string
  reservationId: number
  userId: number
  venueId: number
  venueName?: string
  totalAmount: number
  originalAmount?: number
  memberDiscountAmount?: number
  couponDiscountAmount?: number
  activityDiscountAmount?: number
  payableAmount?: number
  paidAmount?: number
  feeAmount?: number
  netAmount?: number
  pricingSnapshotJson?: string
  promotionCampaignId?: number
  priceRuleId?: number
  couponTemplateId?: number
  userCouponId?: number
  couponLockToken?: string
  status: string
  payType?: string
  /** 稳定支付工具事实（后端 biz_order.payment_tool_type），储值支付为 STORED_VALUE。 */
  paymentToolType?: string
  payTime?: string
  expireTime?: string
  createTime?: string
  updateTime?: string
  /** 用户侧本人退款概要（G-T1，仅订单列表返回；无退款时缺省）。 */
  refundSummary?: OrderRefundSummary | null
}

export type DevPaymentScenario =
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELED'
  | 'TIMEOUT'
  | 'DUPLICATE'
  | 'AMOUNT_MISMATCH'
  | 'SIGNATURE_INVALID'

export interface PaymentAction {
  type: 'DEV_SIMULATION' | 'SUBMIT_FORM'
  method?: 'POST'
  url?: string
  fields?: Record<string, string>
}

export interface PaymentIntentResponse {
  paymentNo: string
  orderId: number
  orderNo: string
  providerEnv: string
  providerName: string
  configVersion: string
  status: string
  payableAmount: number
  paidAmount: number
  feeAmount: number
  netAmount: number
  currency: string
  externalRef: string
  expireTime: string
  requestSummaryJson: string
  responseSummaryJson: string
  providerKey?: string | null
  paymentAction?: PaymentAction | null
}

export interface PaymentCallbackProcessResult {
  paymentNo: string
  orderId: number
  status: string
  orderStatus: string
}

export interface PaymentProviderOption {
  providerKey: string
  providerEnv: string
  providerName: string
  displayName: string
  configVersion: string
  enabled: boolean
  defaultProvider: boolean
}

export interface PaymentAdminQueryParams {
  providerEnv?: string
  providerName?: string
  status?: string
  orderNo?: string
  venueId?: number
  startTime?: string
  endTime?: string
  pageNum?: number
  pageSize?: number
}

export type PaymentAdminOverviewQueryParams = Omit<PaymentAdminQueryParams, 'pageNum' | 'pageSize'>
export type PaymentAttemptQueryParams = PaymentAdminQueryParams
export type PaymentCallbackQueryParams = PaymentAdminQueryParams
export type ReconciliationItemQueryParams = PaymentAdminQueryParams

export interface FailureReasonMetric {
  failureCode?: string
  failureMessage?: string
  count: number
}

export interface PaymentAdminOverviewResponse {
  successAmount: number
  feeAmount: number
  netAmount: number
  successCount: number
  failedCount: number
  canceledCount: number
  timeoutCount: number
  failureReasons: FailureReasonMetric[]
  dataGaps: string[]
}

export interface PaymentAttempt {
  id: number
  paymentNo: string
  orderId: number
  orderNo: string
  userId: number
  venueId: number
  reservationId: number
  providerEnv: string
  providerName: string
  channel: string
  status: string
  payableAmount: number
  paidAmount: number
  feeAmount: number
  netAmount: number
  currency: string
  externalRef?: string
  idempotencyKey?: string
  failureCode?: string
  failureMessage?: string
  configVersion: string
  requestSummaryJson?: string
  responseSummaryJson?: string
  metadataJson?: string
  expireTime?: string
  paidTime?: string
  createTime?: string
  updateTime?: string
}

export interface PaymentCallbackLog {
  id: number
  channel: string
  orderNo?: string
  transactionId?: string
  status: string
  message?: string
  sourceType?: string
  confidence?: number
  providerEnv: string
  providerName: string
  externalRef?: string
  failureCode?: string
  configVersion?: string
  durationMs?: number
  createTime?: string
  venueName?: string
}

export interface ReconciliationItem {
  id: number
  reconcileNo: string
  reconcileDate: string
  businessType: string
  providerEnv: string
  providerName: string
  orderId?: number
  refundId?: number
  paymentRecordId?: number
  paymentAttemptId?: number
  externalRef?: string
  systemAmount: number
  providerAmount: number
  feeAmount: number
  netAmount: number
  differenceAmount: number
  status: string
  adjustmentAmount?: number
  adjustmentReason?: string
  operatorId?: number
  evidenceJson?: string
  metadataJson?: string
  createTime?: string
  updateTime?: string
}

export interface ReconciliationRunPayload {
  reconcileDate?: string
  /* 后端 ReconciliationRunRequest 两个渠道字段均可空（resolveProvider 回落默认渠道）；
     仅 finance:reconcile 权限的主体读不到 providers 目录（需 payment:admin），此时不下发 */
  providerEnv?: string
  providerName?: string
}

export interface ReconciliationRunResponse {
  reconcileDate?: string
  providerEnv?: string
  providerName?: string
  totalCount?: number
  matchedCount: number
  differenceCount: number
  missingProviderCount?: number
  missingSystemCount?: number
}

export interface AdjustReconciliationPayload {
  adjustmentAmount: number
  adjustmentReason: string
}

export interface RefundRecord {
  id: number
  refundNo: string
  orderId: number
  userId: number
  refundAmount: number
  refundReason: string
  status: string
  auditReason?: string
  createTime?: string
  updateTime?: string
}

export interface Notice {
  id: number
  title: string
  content: string
  type: string
  status: string
  publishTime?: string
  deliveryBatchId?: number
  targetScope?: string
  targetRuleJson?: string
  experimentId?: number
}

export interface PublicNotice {
  id: number
  title: string
  content: string
  publishTime?: string
}

export type PlacementSourceType = 'GLOBAL_NOTICE' | 'PRICE_PROMOTION' | 'COMMUNITY_ACTIVITY'
export type PlacementAudienceType = 'ALL_AUTHENTICATED' | 'MEMBER_LEVEL'
export type PlacementCandidateStatus = 'DRAFT' | 'PUBLISHED' | 'PAUSED' | 'RETIRED'
export type PlacementEventType = 'CLIENT_VIEWABLE' | 'CLICK'

export interface HomePrimaryDecision {
  decisionId: number
  candidateVersionId: number
  token: string
  reused: boolean
  title: string
  summary: string
  imageUrl?: string | null
  deepLink: string
  expiresAt: string
}

export interface PlacementEventRequest {
  eventType: PlacementEventType
  token: string
  visibleRatio?: number
  visibleDurationMs?: number
}

export interface PlacementEventResult {
  resultStatus: 'ACCEPTED' | 'REPLAYED' | 'REJECTED'
  rejectionCode?: string | null
  eventType: PlacementEventType
}

export interface PlacementSlotResponse {
  id: number
  slotCode: string
  slotName: string
  status: string
}

export interface PlacementCandidateVersionResponse {
  id: number
  slotId: number
  candidateNo: string
  sourceType: PlacementSourceType
  sourceId: number
  sourceVersion?: string | null
  effectiveFrom: string
  effectiveUntil: string
  audienceType: PlacementAudienceType
  audienceLevelCode?: string | null
  priority: number
  frequencyCapPerUser: number
  frequencyCapWindowHours: number
  ruleVersion?: string | null
  status: PlacementCandidateStatus
  publishedAt?: string | null
}

export interface PlacementSourcePreviewResponse {
  sourceType: PlacementSourceType
  sourceId: number
  sourceVersion?: string | null
  title: string
  summary: string
  imageUrl?: string | null
  deepLink: string
  sourceEffectiveFrom?: string | null
  sourceEffectiveUntil?: string | null
}

export interface PlacementDecisionAuditResponse {
  id: number
  decisionNo: string
  userId: number
  slotId: number
  candidateVersionId: number
  status: string
  expiresAt?: string | null
  consumedAt?: string | null
  createTime?: string | null
  sourceSnapshot?: {
    sourceType?: PlacementSourceType | null
    sourceId?: number | null
    sourceVersion?: string | null
    title?: string | null
    summary?: string | null
    imageUrl?: string | null
    deepLink?: string | null
  } | null
}

export interface PlacementEventAuditResponse {
  id: number
  eventNo: string
  eventType: PlacementEventType
  decisionId: number
  userId: number
  slotId: number
  candidateVersionId: number
  resultStatus: string
  rejectionCode?: string | null
  visibleRatio?: number | null
  visibleDurationMs?: number | null
  occurredAt?: string | null
}

export interface CreatePlacementCandidateRequest {
  slotId: number
  sourceType: PlacementSourceType
  sourceId: number
  effectiveFrom: string
  effectiveUntil: string
  audienceType?: PlacementAudienceType
  audienceLevelCode?: string
  priority?: number
  frequencyCapPerUser?: number
  frequencyCapWindowHours?: number
}

export interface AudiencePreviewResponse {
  targetScope: string
  audienceCount: number
  deliverableCount: number
  excludedCount: number
  excludeReasonCounts: Record<string, number>
  samples: Array<{ userId: number; deliverable: boolean; excludeReason?: string }>
}

export interface TransactionalEmailPreferenceResponse {
  transactionalEmailEnabled: boolean
  emailConfigured: boolean
  emailEligible: boolean
  maskedEmail: string | null
  addressValidated: boolean
  addressVerified: false
  exclusionCode: string | null
}

export type AdvertisingEventType = 'CLIENT_VIEWABLE' | 'CLICK'

export interface AdvertisingPrivacyPreferenceResponse {
  commercialEnabled: boolean
  measurementEnabled: boolean
  policyVersion: string
  changedAt?: string | null
}

export interface HomeCommercialDecision {
  decisionId: number
  creativeVersionId: number
  token: string | null
  reused: boolean
  adLabel: string
  accessibleAdLabel: string
  headline: string
  bodyText: string
  callToAction: 'VIEW' | 'BOOK' | 'LEARN_MORE' | string
  targetType: string
  targetId?: number | null
  deepLink: string
  policyVersion: string
  expiresAt: string
}

export interface AdvertisingEventRequest {
  eventType: AdvertisingEventType
  token: string
  visibleRatio?: number
  visibleDurationMs?: number
}

export interface AdvertisingEventResult {
  resultStatus: 'ACCEPTED' | 'REJECTED'
  rejectionCode?: string | null
  eventType: AdvertisingEventType
  replayed: boolean
}

export interface AdvertisingAnalysisOverview {
  from: string
  until: string
  decisions: number
  viewableEvents: number
  clickEvents: number
  attributedReservations: number
  gapReservations: number
  revokedReservations: number
  noValidClickCallbacks: number
  privacyDisabledCallbacks: number
  pendingRetryCallbacks: number
  unmatchedClicks: number
}

export type AdvertisingLifecycleStatus =
  | 'DRAFT' | 'PENDING_REVIEW' | 'REJECTED' | 'APPROVED'
  | 'PUBLISHED' | 'PAUSED' | 'RETIRED'

export type AdvertisingTargetType = 'VENUE_LIST' | 'VENUE_DETAIL' | 'ACTIVITY_DETAIL'

export type AdvertisingCallToAction = 'VIEW' | 'BOOK' | 'LEARN_MORE'

export interface AdvertisingAdvertiserResponse {
  id: number
  advertiserNo: string
  advertiserName: string
  status: 'ACTIVE' | 'SUSPENDED' | 'RETIRED' | string
  createTime?: string | null
  updateTime?: string | null
}

/* B5-G5：商业生命周期审计出参为白名单摘要，不含审核备注原文（reasonText） */
export interface AdvertisingAuditResponse {
  id: number
  auditNo: string
  entityType: 'ADVERTISER' | 'CAMPAIGN' | 'CREATIVE' | string
  entityId: number
  entityNo?: string | null
  venueId?: number | null
  relatedEntityType?: string | null
  relatedEntityId?: number | null
  action: string
  sourceType?: string | null
  reasonCode?: string | null
  beforeStatus?: string | null
  afterStatus?: string | null
  resultStatus?: string | null
  createTime?: string | null
}

export interface AdvertisingCampaignResponse {
  id: number
  campaignNo: string
  advertiserId: number
  venueId?: number | null
  campaignName: string
  effectiveFrom: string
  effectiveUntil: string
  status: AdvertisingLifecycleStatus | string
  submittedAt?: string | null
  reviewedAt?: string | null
  publishedAt?: string | null
  pausedAt?: string | null
  retiredAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface AdvertisingCreativeResponse {
  id: number
  creativeNo: string
  campaignId: number
  versionNo: number
  headline: string
  bodyText: string
  callToAction: AdvertisingCallToAction | string
  targetType: AdvertisingTargetType | string
  targetId?: number | null
  status: AdvertisingLifecycleStatus | string
  submittedAt?: string | null
  reviewedAt?: string | null
  publishedAt?: string | null
  pausedAt?: string | null
  retiredAt?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface CreateAdvertisingAdvertiserRequest {
  advertiserName: string
}

export interface CreateAdvertisingCampaignRequest {
  advertiserId: number
  venueId?: number | null
  campaignName: string
  effectiveFrom: string
  effectiveUntil: string
}

export interface CreateAdvertisingCreativeRequest {
  campaignId: number
  versionNo: number
  headline: string
  bodyText: string
  callToAction: AdvertisingCallToAction
  targetType: AdvertisingTargetType
  targetId?: number | null
}

export interface AdvertisingReviewDecisionRequest {
  reasonCode: string
  reasonText: string
}

export interface AdvertisingTransitionRequest {
  reasonCode?: string
  reasonText?: string
}

export interface NotificationProviderOption {
  providerKey: string
  providerDomain: string
  providerEnv: string
  providerName: string
  channel: string
  displayName: string
  configVersion: string
  enabled: boolean
  defaultProvider: boolean
}

export interface NoticeDeliveryBatchResponse {
  batchId: number
  batchNo: string
  noticeId: number
  targetScope?: string
  status: string
  audienceCount: number
  deliverableCount: number
  excludedCount?: number
  sentCount: number
  failedCount: number
  readCount: number
  unreadCount: number
  providerEnv: string
  providerName: string
  configVersion?: string
  sourceType?: string
  publisherId?: number
  experimentId?: number
  createTime?: string
  updateTime?: string
}

export interface NoticeDeliveryOverviewResponse {
  batchCount: number
  audienceCount: number
  sentCount: number
  acceptedCount: number
  retryWaitingCount: number
  failedCount: number
  deliveredCount: number
  bouncedCount: number
  unreadCount: number
  readCount: number
  readRate: number
  failureReasonCounts: Record<string, number>
  providerEnv: string
  providerName: string
  configVersion: string
  channel: string
  dataGaps: string[]
}

export interface NoticeDeliveryOutboxResponse {
  outboxId: number
  outboxNo: string
  eventType: string
  recipientKind: string
  maskedRecipient: string | null
  channel: string
  providerKey: string
  providerEnv: string
  providerName: string
  configVersion: string
  templateKey: string
  templateVersion: string
  status: string
  statusText: string
  acceptanceStatus: string | null
  downstreamStatus: string | null
  attemptCount: number
  nextRetryTime?: string | null
  failureCode?: string | null
  createTime?: string
  updateTime?: string
  acceptedAt?: string | null
}

export interface NoticeAudienceResponse {
  audienceId: number
  batchId: number
  noticeId: number
  userId: number
  venueId?: number
  memberLevelId?: number
  segmentCode?: string
  experimentVariantId?: number
  deliveryStatus: string
  readStatus: string
  excludeReason?: string
  messageId?: number
  lastDeliveryLogId?: number
  outboxId?: number
  readTime?: string
  createTime?: string
  updateTime?: string
}

export interface NoticeExperimentVariant {
  id?: number
  experimentId?: number
  variantCode: string
  title: string
  content: string
  allocationPercent: number
}

export interface NoticeExperimentResponse {
  id: number
  experimentNo?: string
  name: string
  status: string
  targetMetric?: string
  sampleScope?: string
  startTime?: string
  endTime?: string
  ownerId?: number
  variants?: NoticeExperimentVariant[]
}

export interface NoticeExperimentVariantResult {
  variantId: number
  variantCode: string
  title: string
  allocationPercent: number
  audienceCount: number
  sentCount: number
  failedCount: number
  readCount: number
  readRate: number
}

export interface NoticeExperimentResultResponse {
  experimentId: number
  experimentNo: string
  name: string
  status: string
  targetMetric: string
  sampleScope: string
  providerKey: string
  providerEnv: string
  providerName: string
  channel: string
  displayName: string
  configVersion: string
  audienceCount: number
  sentCount: number
  failedCount: number
  readCount: number
  readRate: number
  variants: NoticeExperimentVariantResult[]
}

export interface Message {
  id: number
  title: string
  content: string
  type: string
  businessId?: number
  readFlag: number
  createTime: string
  readTime?: string
}
