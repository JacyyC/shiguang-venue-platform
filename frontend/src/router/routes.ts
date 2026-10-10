import type { RouteRecordRaw } from 'vue-router'
import { CONTENT_ADMIN_ENTRY_PERMISSION } from '@/utils/contentAdminAccess'

export const PLACEMENT_ENTRY_PERMISSIONS = ['placement:manage', 'placement:publish', 'placement:audit:view']
export const ADVERTISING_ANALYSIS_ENTRY_PERMISSIONS = ['advertising:analysis:view']
export const ADVERTISING_MANAGE_ENTRY_PERMISSIONS = ['advertising:manage', 'advertising:review', 'advertising:publish', 'advertising:audit:view']

export const routes: RouteRecordRaw[] = [
  { path: '/engineering', name: 'Engineering', component: () => import('@/views/engineering/EngineeringView.vue'), meta: { public: true } },
  { path: '/login', name: 'Login', component: () => import('@/views/public/ShiguangAuthView.vue'), meta: { public: true } },
  { path: '/register', name: 'Register', component: () => import('@/views/public/ShiguangAuthView.vue'), meta: { public: true } },
  { path: '/forgot-password', name: 'ForgotPassword', component: () => import('@/views/public/ShiguangAuthView.vue'), meta: { public: true } },
  { path: '/403', name: 'Forbidden', component: () => import('@/views/public/ForbiddenView.vue'), meta: { public: true } },
  {
    path: '/',
    // 拾光一级浏览与旧页共用一个父级：meta.layout==='shiguang' 的四个一级入口走新布局，其余保持旧 UserLayout
    component: () => import('@/layouts/ShiguangLayoutSwitch.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', name: 'UserHome', component: () => import('@/views/user/shiguang/ShiguangHomeView.vue'), meta: { menuKey: 'user:view', layout: 'shiguang' } },
      { path: 'community', name: 'CommunityGroups', component: () => import('@/views/user/shiguang/community/ShiguangCommunityHomeView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'community/groups/:id', name: 'CommunityGroup', component: () => import('@/views/user/shiguang/community/ShiguangCommunityGroupView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'community/posts/:id', name: 'CommunityPost', component: () => import('@/views/user/shiguang/community/ShiguangCommunityPostView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'activities', name: 'ActivityHub', component: () => import('@/views/user/shiguang/ShiguangActivityListView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'activities/:id/manage', name: 'ActivityManage', component: () => import('@/views/user/shiguang/ShiguangActivityManageView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'activities/:id/result', name: 'ActivityResult', component: () => import('@/views/user/shiguang/ShiguangActivityResultView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'activities/:id', name: 'ActivityDetail', component: () => import('@/views/user/shiguang/ShiguangActivityDetailView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'companion-requests/:id/manage', name: 'CompanionManage', component: () => import('@/views/user/shiguang/ShiguangActivityManageView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'companion-requests/:id/result', name: 'CompanionResult', component: () => import('@/views/user/shiguang/ShiguangActivityResultView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'companion-requests/:id', name: 'CompanionDetail', component: () => import('@/views/user/shiguang/ShiguangActivityDetailView.vue'), meta: { menuKey: 'community:access', layout: 'shiguang' } },
      { path: 'venues', name: 'VenueList', component: () => import('@/views/user/shiguang/ShiguangVenueListView.vue'), meta: { menuKey: 'venue:view', layout: 'shiguang' } },
      { path: 'venues/:id', name: 'VenueDetail', component: () => import('@/views/user/VenueDetailView.vue'), meta: { menuKey: 'venue:view', layout: 'shiguang' } },
      { path: 'me', name: 'MeOverview', component: () => import('@/views/user/shiguang/personal/ShiguangPersonalView.vue'), meta: { menuKey: 'user:view', layout: 'shiguang' } },
      { path: 'me/time', name: 'MyTime', component: () => import('@/views/user/shiguang/ShiguangMeTimeView.vue'), meta: { menuKey: 'user:view', layout: 'shiguang' } },
      { path: 'reservation/confirm', name: 'ReservationConfirm', component: () => import('@/views/user/ReservationConfirmView.vue'), meta: { menuKey: 'reservation:my', layout: 'shiguang' } },
      { path: 'reservation/result/:id', name: 'ReservationResult', component: () => import('@/views/user/shiguang/ShiguangReservationResultView.vue'), meta: { menuKey: 'reservation:my', layout: 'shiguang' } },
      { path: 'me/reservations', name: 'MyReservations', component: () => import('@/views/user/shiguang/transactions/ShiguangTransactionsView.vue'), meta: { menuKey: 'reservation:my', layout: 'shiguang' } },
      { path: 'me/checkins', name: 'MyCheckins', component: () => import('@/views/user/shiguang/transactions/ShiguangTransactionsView.vue'), meta: { menuKey: 'reservation:checkin:me', layout: 'shiguang' } },
      { path: 'me/feedback', name: 'MyFeedback', component: () => import('@/views/user/shiguang/personal/ShiguangPersonalView.vue'), meta: { menuKey: 'reservation:my', layout: 'shiguang' } },
      { path: 'me/orders', name: 'MyOrders', component: () => import('@/views/user/shiguang/transactions/ShiguangTransactionsView.vue'), meta: { menuKey: 'order:my', layout: 'shiguang' } },
      { path: 'me/orders/:id/pay', name: 'Payment', component: () => import('@/views/user/shiguang/transactions/ShiguangPaymentView.vue'), meta: { menuKey: 'order:my', layout: 'shiguang' } },
      { path: 'me/payments/alipay/sandbox/return', name: 'AlipaySandboxReturn', component: () => import('@/views/user/shiguang/transactions/ShiguangSandboxReturnView.vue'), meta: { menuKey: 'order:my', layout: 'shiguang' } },
      { path: 'me/membership', name: 'Membership', component: () => import('@/views/user/shiguang/member/ShiguangMemberHomeView.vue'), meta: { menuKey: 'member:me', layout: 'shiguang' } },
      { path: 'me/coupons', name: 'Coupons', component: () => import('@/views/user/shiguang/member/ShiguangMemberCouponsView.vue'), meta: { menuKey: 'coupon:me', layout: 'shiguang' } },
      { path: 'me/stored-value', name: 'StoredValue', component: () => import('@/views/user/shiguang/member/ShiguangMemberStoredView.vue'), meta: { menuKey: 'stored-value:me', layout: 'shiguang' } },
      { path: 'me/favorites', name: 'Favorites', component: () => import('@/views/user/shiguang/personal/ShiguangPersonalView.vue'), meta: { menuKey: 'funnel:favorite:me', layout: 'shiguang' } },
      { path: 'me/profile', alias: '/user/profile', name: 'Profile', component: () => import('@/views/user/shiguang/personal/ShiguangPersonalView.vue'), meta: { menuKey: 'user:view', layout: 'shiguang' } },
      { path: 'me/messages', name: 'Messages', component: () => import('@/views/user/shiguang/personal/ShiguangPersonalView.vue'), meta: { layout: 'shiguang' } }
    ]
  },
  {
    path: '/content-admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, authorizationKey: CONTENT_ADMIN_ENTRY_PERMISSION, adminTitle: '内容运营后台' },
    children: [
      // BATCH-04 S07：内容审核与平台治理旧正文替换为拾光治理面（path/name/meta 不变）
      { path: '', name: 'ContentAdmin', component: () => import('@/views/admin/shiguang/governance/ShiguangContentGovernanceView.vue'), meta: { authorizationKey: CONTENT_ADMIN_ENTRY_PERMISSION } },
      {
        path: 'placements',
        name: 'ContentAdminPlacements',
        // BATCH-05 S06：旧正文替换为拾光自营宣传面（path/name/meta 不变）
        component: () => import('@/views/admin/shiguang/placement/ShiguangPlacementView.vue'),
        meta: {
          authorizationKey: CONTENT_ADMIN_ENTRY_PERMISSION,
          authorizationAny: PLACEMENT_ENTRY_PERMISSIONS
        }
      },
      {
        path: 'advertising',
        name: 'ContentAdminAdvertising',
        // BATCH-05 S07：旧正文替换为拾光商业广告面（path/name/meta 不变）
        component: () => import('@/views/admin/shiguang/advertising/ShiguangAdvertisingView.vue'),
        meta: {
          authorizationKey: CONTENT_ADMIN_ENTRY_PERMISSION,
          authorizationAny: ADVERTISING_MANAGE_ENTRY_PERMISSIONS
        }
      },
      {
        path: 'advertising/analysis',
        name: 'ContentAdminAdvertisingAnalysis',
        // BATCH-05 S07：旧正文替换为拾光商业分析面（path/name/meta 不变）
        component: () => import('@/views/admin/shiguang/advertising/ShiguangAdvertisingAnalysisView.vue'),
        meta: {
          authorizationKey: CONTENT_ADMIN_ENTRY_PERMISSION,
          authorizationAny: ADVERTISING_ANALYSIS_ENTRY_PERMISSIONS
        }
      }
    ]
  },
  {
    path: '/venue-admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, roles: ['VENUE_ADMIN', 'SYSTEM_ADMIN'], adminTitle: '空间运营后台' },
    children: [
      // BATCH-02 S05：dashboard/reservations/checkins/orders 旧正文替换为拾光履约面（初始页签由路由名决定），path/name/menuKey 不变
      { path: '', name: 'VenueAdminDashboard', component: () => import('@/views/admin/shiguang/fulfillment/ShiguangVenueFulfillmentView.vue'), meta: { menuKey: 'venue-admin:view' } },
      { path: 'reservations', name: 'VenueAdminReservations', component: () => import('@/views/admin/shiguang/fulfillment/ShiguangVenueFulfillmentView.vue'), meta: { menuKey: 'reservation:audit' } },
      { path: 'checkins', name: 'VenueAdminCheckins', component: () => import('@/views/admin/shiguang/fulfillment/ShiguangVenueFulfillmentView.vue'), meta: { menuKey: 'reservation:checkin:manage' } },
      // BATCH-03 S04：site-ops/assets 旧正文替换为拾光现场与资产面（初始页签由路由名决定），path/name/menuKey 不变
      { path: 'site-ops', name: 'VenueAdminSiteOps', component: () => import('@/views/admin/shiguang/siteops/ShiguangVenueSiteOpsView.vue'), meta: { menuKey: 'site:ops:manage' } },
      { path: 'assets', name: 'VenueAdminAssets', component: () => import('@/views/admin/shiguang/siteops/ShiguangVenueSiteOpsView.vue'), meta: { menuKey: 'asset:manage' } },
      // BATCH-03 S05：customer-service 旧正文替换为拾光客服与反馈面（path/name/menuKey 不变）
      { path: 'customer-service', name: 'VenueAdminCustomerService', component: () => import('@/views/admin/shiguang/service/ShiguangVenueCustomerServiceView.vue'), meta: { menuKey: 'customer-service:manage' } },
      // BATCH-04 S06：funnel 旧正文替换为拾光场馆漏斗面（path/name/menuKey 不变）
      { path: 'funnel', name: 'VenueAdminFunnel', component: () => import('@/views/admin/shiguang/funnel/ShiguangVenueFunnelView.vue'), meta: { menuKey: 'funnel:analytics:view' } },
      // BATCH-02 S04：spaces/calendar 旧正文替换为拾光资源面（初始页签由路由名决定），path/name/menuKey 不变
      { path: 'spaces', name: 'VenueAdminSpaces', component: () => import('@/views/admin/shiguang/resources/ShiguangVenueResourcesView.vue'), meta: { menuKey: 'venue:space:manage' } },
      { path: 'calendar', name: 'VenueAdminCalendar', component: () => import('@/views/admin/shiguang/resources/ShiguangVenueResourcesView.vue'), meta: { menuKey: 'venue:inventory:view' } },
      { path: 'orders', name: 'VenueAdminOrders', component: () => import('@/views/admin/shiguang/fulfillment/ShiguangVenueFulfillmentView.vue'), meta: { menuKey: 'order:admin' } },
      // BATCH-03 S06：notices 旧正文替换为拾光公告与通知触达面（初始页签由路由名决定），path/name/menuKey 不变
      { path: 'notices', name: 'VenueAdminNotices', component: () => import('@/views/admin/shiguang/notice/ShiguangNoticeJourneyView.vue'), meta: { menuKey: 'notice:manage' } }
    ]
  },
  {
    path: '/system-admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, roles: ['SYSTEM_ADMIN'], adminTitle: '系统管理后台' },
    children: [
      // BATCH-02 S06：五条平台旧正文替换为拾光平台面（初始页签由路由名/hash 决定），path/name/menuKey 不变
      { path: '', name: 'SystemAdminDashboard', component: () => import('@/views/admin/shiguang/platform/ShiguangPlatformAdminView.vue'), meta: { menuKey: 'system:view' } },
      // BATCH-02 S07：providers 旧正文替换为拾光 Provider 面（渠道由路由名/hash 决定），path/name/menuKey 不变
      { path: 'providers', name: 'SystemProviders', component: () => import('@/views/admin/shiguang/providers/ShiguangProviderObservatoryView.vue'), meta: { menuKey: 'provider:observe' } },
      // BATCH-03 S06：notice-delivery/notice-experiments 旧正文替换为同一个拾光公告与通知触达面（初始页签由路由名决定），path/name/menuKey 不变
      { path: 'notice-delivery', name: 'SystemNoticeDelivery', component: () => import('@/views/admin/shiguang/notice/ShiguangNoticeJourneyView.vue'), meta: { menuKey: 'notice:analytics' } },
      { path: 'notice-experiments', name: 'SystemNoticeExperiments', component: () => import('@/views/admin/shiguang/notice/ShiguangNoticeJourneyView.vue'), meta: { menuKey: 'notice:analytics' } },
      // BATCH-04 S04：members/reward-catalog 两条旧正文替换为同一个拾光会员与权益面（初始页签由路由名决定）
      { path: 'members', name: 'SystemMembers', component: () => import('@/views/admin/shiguang/member/ShiguangMemberAdminView.vue'), meta: { menuKey: 'member:manage' } },
      { path: 'reward-catalog', name: 'SystemRewardCatalog', component: () => import('@/views/admin/shiguang/member/ShiguangMemberAdminView.vue'), meta: { menuKey: 'member:manage' } },
      // BATCH-04 S05：promotions 旧正文替换为拾光营销面；alias 与 menuKey 保持不变
      { path: 'promotions', alias: '/system-admin/promotion-pricing', name: 'SystemPromotions', component: () => import('@/views/admin/shiguang/promotion/ShiguangPromotionAdminView.vue'), meta: { menuKey: 'promotion:manage' } },
      // BATCH-05 S04：payments/reconciliation 旧正文替换为拾光财务面（工作面由路由名决定），path/name/menuKey 不变
      { path: 'payments', name: 'SystemPayments', component: () => import('@/views/admin/shiguang/finance/ShiguangFinanceView.vue'), meta: { menuKey: 'payment:admin' } },
      { path: 'reconciliation', name: 'SystemReconciliation', component: () => import('@/views/admin/shiguang/finance/ShiguangFinanceView.vue'), meta: { menuKey: 'finance:reconcile' } },
      // BATCH-05 S05：stored-value 旧正文替换为拾光储值财务面（工作面由路由名决定），path/name/menuKey 不变
      { path: 'stored-value', name: 'SystemStoredValue', component: () => import('@/views/admin/shiguang/finance/ShiguangStoredValueView.vue'), meta: { menuKey: 'stored-value:finance:view' } },
      { path: 'users', name: 'SystemUsers', component: () => import('@/views/admin/shiguang/platform/ShiguangPlatformAdminView.vue'), meta: { menuKey: 'system:user:manage' } },
      { path: 'roles', name: 'SystemRoles', component: () => import('@/views/admin/shiguang/platform/ShiguangPlatformAdminView.vue'), meta: { menuKey: 'system:role:manage' } },
      { path: 'configs', name: 'SystemConfigs', component: () => import('@/views/admin/shiguang/platform/ShiguangPlatformAdminView.vue'), meta: { menuKey: 'system:config:manage' } },
      { path: 'logs', name: 'SystemLogs', component: () => import('@/views/admin/shiguang/platform/ShiguangPlatformAdminView.vue'), meta: { menuKey: 'system:log:view' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/public/NotFoundView.vue'), meta: { public: true } }
]
