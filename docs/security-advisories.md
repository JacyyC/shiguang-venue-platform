# 依赖公告与适用性审查

2026-10-10 的 Maven/OSV 复核仍会命中 Spring MVC 6.2.19 的两条公告。公开 6.2 版本尚没有对应修复；已知修复为 Spring 7.0.9，迁移到该主版本需要单独验证。

| 公告 | 触发条件 | 本项目当前检查 |
| --- | --- | --- |
| [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) | SSE 中渲染视图片段 | `NoticeSseController` / `SseConnectionManager` 发送 JSON 数据，没有 `FragmentsRendering` |
| [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) | `XsltView`、通配映射和隐式视图名组合 | 服务端是 REST/JSON API，没有 `XsltView` 或 `XsltViewResolver` 配置 |

这两条被记录为当前使用路径不适用，**不表示依赖本身已修复，也不表示完成了全面安全审计**。

[机器可读审查](security-advisories.json)只匹配指定包、版本和公告 ID，2026-11-10 到期。每次审计重新检查相关源码标识；新增相关功能、版本变化或过期后，例外失效并使检查失败。更复杂的动态配置仍需人工复核。

其余命中组件通过版本升级或替换旧 SDK 传递依赖处理。完整实际结果见[发布验证](release-verification.md)。
