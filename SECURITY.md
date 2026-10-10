# 安全问题

本项目当前是学习和二次开发起点，支持维护的版本为最新的 v0.1.x 开源版本。生产部署和真实资金能力未被验证。

已启用 Private vulnerability reporting，请通过仓库 Security 页的 [Report a vulnerability](https://github.com/JacyyC/shiguang-venue-platform/security/advisories/new) 私密报告漏洞，不公开利用步骤、真实凭据或个人信息。

建议报告包含受影响版本、最小复现、所需权限和影响范围。维护者目前没有承诺固定响应时限。

## 本地使用

- 默认账号和示例凭据用于本地合成演示，不用于公开服务。
- `.env.local`、上传文件、数据库和运行日志不进入 Git。
- DEV 支付不能代表真实付款；生产储值入口保持关闭。
- 审计发现依赖公告不等于已经证明某条产品路径可利用；仍应升级并回归验证。
- 通过 `npm audit`、Maven/OSV 审计和 Gitleaks 定期检查。扫描方式见[验证说明](docs/verification.md)。
