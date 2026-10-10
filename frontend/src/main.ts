import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { vReveal } from './directives/reveal'
import { useThemeStore } from './stores/theme'
import './styles/main.css'
// 拾光接入样式：仅作用于 .sgv-auth / .sgv-home / .sgv-entry 三根，不影响旧页（SHIGUANG-PREVIEW-INTEGRATION-20260909）
import './styles/shiguang.css'
// 拾光预约链路样式：仅作用于 .sgv-booking（.sgv-home 后代），必须在 shiguang.css 之后加载（SHIGUANG-BOOKING-REFACTOR-20260910）
import './styles/shiguang-booking.css'
// 拾光活动与同行 V02 样式：仅作用于 .sgv-activity（.sgv-home 后代），必须在 shiguang.css 之后加载（SHIGUANG-ACTIVITY-REFACTOR-20260911）
import './styles/shiguang-activity.css'
// 拾光社区 V1 样式：仅作用于 .sgv-community（.sgv-home 后代），必须在 shiguang.css 之后加载（SHIGUANG-COMMUNITY-REFACTOR-20260911）
import './styles/shiguang-community.css'
// 拾光会员中心样式：仅作用于 .sgv-member（.sgv-home 后代），必须在 shiguang.css 之后加载（SHIGUANG-MEMBER-REFACTOR-20260912）
import './styles/shiguang-member.css'
// 拾光用户服务样式（交易面/个人面）：仅作用于 .sgv-user-service（.sgv-home 后代），必须在 shiguang.css 之后加载（SHIGUANG-BATCH-01-REFACTOR-20260913）
import './styles/shiguang-user-service.css'
// 拾光后台公共框架样式：仅作用于 .sga-framework 根（AdminLayout 框架壳），token 自含明暗两套，不影响旧正文（SHIGUANG-BATCH-01-REFACTOR-20260913）
import './styles/shiguang-admin-framework.css'
// 拾光后台资源面样式：仅作用于 .sga-venue（第二批资源工作面正文），token 自含明暗两套（SHIGUANG-BATCH-02-REFACTOR-20260913）
import './styles/shiguang-admin-venue.css'
// 拾光后台履约面样式：仅作用于 .sga-fulfillment（第二批履约工作面正文），token 自含明暗两套（SHIGUANG-BATCH-02-REFACTOR-20260913）
import './styles/shiguang-admin-fulfillment.css'
// 拾光后台平台面样式：仅作用于 .sga-c（第二批平台工作面正文；S07 Provider 面共用同一作用域），token 自含明暗两套（SHIGUANG-BATCH-02-REFACTOR-20260913）
import './styles/shiguang-admin-platform.css'
// 拾光后台现场与资产面样式：仅作用于 .sga-siteops（第三批现场与资产工作面正文），token 自含明暗两套（SHIGUANG-BATCH-03-REFACTOR-20260914）
import './styles/shiguang-admin-siteops.css'
// 拾光后台客服与公告面样式：作用于 .sga-service 与 .sga-notice（第三批客服与反馈、公告与通知两面共用 a-* 基元），token 自含明暗两套（SHIGUANG-BATCH-03-REFACTOR-20260914）
import './styles/shiguang-admin-contact.css'
// 拾光后台 B 组三面共用样式：作用于 .sga-ops（第四批会员与权益、促销与定价、场馆漏斗三面共用 b-* 基元），token 自含明暗两套（SHIGUANG-BATCH-04-REFACTOR-20260914 S04）
import './styles/shiguang-admin-ops.css'

// BATCH-04 S07：平台治理面作用域样式（.sga-gov，机械迁移 ref/g/g.css + shared/base.css）
import './styles/shiguang-admin-governance.css'
// BATCH-05 S04：财务面作用域样式（.sga-fin，机械迁移 ref/h/h.css + shared/base.css）
import './styles/shiguang-admin-finance.css'
// V4 展示层自托管字体子集（零外部 CDN；浏览器按 unicode-range 按需拉取命中分片）
import './assets/fonts/display/display-fonts.css'
import './assets/fonts/display/display-fonts-v42.css'
// V11 hero 艺术字：志莽行书子集（仅覆盖首页语气层 66 字符）
import './assets/fonts/display/display-fonts-v43.css'

const app = createApp(App)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.directive('reveal', vReveal)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
useThemeStore().init()
app.mount('#app')
