# GitHub 发布资料

## 建议仓库信息

- Repository name：`shiguang-venue-platform`
- Description：`Vue 3 + Spring Boot 场地预约与社区平台，包含请求恢复、资源并发与 DEV 账务的可复现实验。`
- Topics：`vue`、`typescript`、`spring-boot`、`java`、`reservation`、`booking-system`、`mysql`、`redis`、`testcontainers`、`vitest`
- 默认分支：`main`
- 首版标签：`v0.1.0`；本地准备阶段使用候选版本，发布后才把候选说明改为正式发布事实。

## 发布前人工确认

1. 确认愿意将自己拥有的项目代码按 MIT 发布，并确认仓库名与公开账号。
2. 阅读[实际验证结果](release-verification.md)和[依赖公告审查](security-advisories.md)。
3. 确认公开目录只包含已审查快照，不将原开发仓的历史、私人配置和开发记录追加进来。
4. 在 GitHub Security 设置启用 Private vulnerability reporting；配置 main 分支保护和 CI 必须通过。
5. 第一次 push 后查看真实 GitHub Actions 结果；本地通过不能代替远程 CI。

本文件是发布准备资料，未创建远程仓库、未推送、未进行公开发布。

## v0.1.0 发布说明草稿

拾光是一个面向场馆、场地、自习室和活动室的预约与社区平台。本版提供用户端、管理端、数据库迁移、测试和本地启动配置。

重点工程案例包括：预约提交响应丢失后的原请求恢复、资源并发与锁范围控制，以及 DEV 储值支付/退款/对账的一致性验证。

项目代码采用 MIT；自托管字体附带原始 OFL 许可。历史可靠性报告明确记录被测版本，不代表当前生产性能。

本版本定位为学习、工程展示和二次开发起点。真实资金、生产部署、多实例长稳与 SLA 尚未验证；默认演示仅绑定本地环回地址。安装和实际检查记录见 README 与 docs。
