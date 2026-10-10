# GitHub 发布资料

## 仓库信息

- 所属账号：`JacyyC`
- 中文展示名：拾光 · 综合预约系统
- Repository name：`shiguang-venue-platform`
- Description：`Vue 3 + Spring Boot 场地预约与社区平台，包含请求恢复、资源并发与 DEV 账务的可复现实验。`
- Topics：`vue`、`typescript`、`spring-boot`、`java`、`reservation`、`booking-system`、`mysql`、`redis`、`testcontainers`、`vitest`
- 默认分支：`main`
- 首版标签：`v0.1.0`；版本下载与正式 Release 状态以 [GitHub Releases](https://github.com/JacyyC/shiguang-venue-platform/releases) 为准。

## 发布授权和检查

1. 维护者于 2026-10-10 确认有权公开源码与文档，采用 MIT，版权署名 JacyyC，并授权在上述账号下创建公开仓库。
2. 阅读[实际验证结果](release-verification.md)和[依赖公告审查](security-advisories.md)。
3. 确认公开目录只包含已审查快照，不将原开发仓的历史、私人配置和开发记录追加进来。
4. 在 GitHub Security 设置启用 Private vulnerability reporting；配置 main 分支保护和 CI 必须通过。
5. 第一次 push 后查看真实 GitHub Actions 结果；本地通过不能代替远程 CI。

已创建[公开仓库](https://github.com/JacyyC/shiguang-venue-platform)，独立审查快照已推送到 `main`，MIT 已被 GitHub 识别。已启用 Private vulnerability reporting。

## 首次 GitHub CI 与修复

[首次运行](https://github.com/JacyyC/shiguang-venue-platform/actions/runs/38052476325)的前端测试、构建、依赖扫描与公开结构检查通过，但另外两项检查失败：

- Gitleaks Action 为首次推送构造了根提交的父提交范围，Git 无法解析该范围，扫描未完成。改为固定 Gitleaks 8.30.1 容器直接扫描完整公开 Git 历史；保留默认规则和已有窄范围的合成数据例外。
- 储值测试中有 9 项报「未完成储值身份核验」：数据库与 JDBC 为 `Asia/Shanghai`，Ubuntu 测试 JVM 默认为 UTC，导致当前核验时间被判断为未来。运行器为测试 JVM 显式设置相同时区；业务身份核验逻辑保持原有要求。

修复后的实际状态见 [GitHub Actions](https://github.com/JacyyC/shiguang-venue-platform/actions/workflows/ci.yml)；每次运行与具体提交关联，不能把一次通过推广为生产验收。

## v0.1.0 发布说明草稿

拾光是一个面向场馆、场地、自习室和活动室的预约与社区平台。本版提供用户端、管理端、数据库迁移、测试和本地启动配置。

重点工程案例包括：预约提交响应丢失后的原请求恢复、资源并发与锁范围控制，以及 DEV 储值支付/退款/对账的一致性验证。

项目代码采用 MIT；自托管字体附带原始 OFL 许可。历史可靠性报告明确记录被测版本，不代表当前生产性能。

本版本定位为学习、工程展示和二次开发起点。真实资金、生产部署、多实例长稳与 SLA 尚未验证；默认演示仅绑定本地环回地址。安装和实际检查记录见 README 与 docs。
