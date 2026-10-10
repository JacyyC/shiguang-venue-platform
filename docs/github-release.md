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

[首次运行](https://github.com/JacyyC/shiguang-venue-platform/actions/runs/38052476325)的前端测试、构建、依赖扫描与公开结构检查通过，但后端、储值集成和扫描任务失败：

- Gitleaks Action 为首次推送构造了根提交的父提交范围，Git 无法解析该范围，扫描未完成。改为固定 Gitleaks 8.30.1 容器直接扫描完整公开 Git 历史；保留默认规则和已有窄范围的合成数据例外。
- 储值测试中有 9 项报「未完成储值身份核验」：数据库与 JDBC 为 `Asia/Shanghai`，Ubuntu 测试 JVM 默认为 UTC，导致当前核验时间被判断为未来。运行器为测试 JVM 显式设置相同时区；业务身份核验逻辑保持原有要求。
- 后端默认 2,076 项测试中有 3 项失败：活动通知重试/过期的 2 项受相同时区假设影响；广告位查询计划 1 项使用了近空表的统计，选择全表扫描。Surefire 明确测试 JVM 时区，广告位夹具同步数据库会话时区并在规模数据装载后刷新统计；索引与查询断言保持原有要求。

[第二次运行](https://github.com/JacyyC/shiguang-venue-platform/actions/runs/38053067560)的储值四组集成和完整历史扫描通过。扫描之后的 shell 测试暴露 macOS 固定 `lsof` 路径；改为发现当前工具路径，并按操作系统选择 `stat` 参数，保留进程所有权、隐私目录权限与拒绝误停的断言。

随后在 UTC 主机模拟检查中，活动通知两项通过，但规模夹具刷新统计后，候选查询仍选择全表扫描（估算 1,000 行）。因此修复实际 Mapper：显式使用 V19 已有的候选排序索引；计划测试改为读取 MyBatis 的真实 SQL 和参数执行 EXPLAIN，并保留统计刷新与非全表扫描断言。没有修改 V1–V36 迁移、候选过滤、排序或返回上限；这种查询提示尚不能替代生产规模分布验证。

修复后的实际状态见 [GitHub Actions](https://github.com/JacyyC/shiguang-venue-platform/actions/workflows/ci.yml)；每次运行与具体提交关联，不能把一次通过推广为生产验收。

## v0.1.0 发布说明草稿

拾光是一个面向场馆、场地、自习室和活动室的预约与社区平台。本版提供用户端、管理端、数据库迁移、测试和本地启动配置。

重点工程案例包括：预约提交响应丢失后的原请求恢复、资源并发与锁范围控制，以及 DEV 储值支付/退款/对账的一致性验证。

项目代码采用 MIT；自托管字体附带原始 OFL 许可。历史可靠性报告明确记录被测版本，不代表当前生产性能。

本版本定位为学习、工程展示和二次开发起点。真实资金、生产部署、多实例长稳与 SLA 尚未验证；默认演示仅绑定本地环回地址。安装和实际检查记录见 README 与 docs。
