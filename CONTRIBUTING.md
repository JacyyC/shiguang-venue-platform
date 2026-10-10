# 参与贡献

欢迎可复现的缺陷报告、文档修正、测试与有清楚需求的小范围改进。

## 开始开发

按 [README](README.md) 和[开发说明](docs/development.md)启动独立本地环境。使用合成数据，不上传真实个人信息、凭据或支付回调。复杂行为变更先开 Issue 说明目标与验收条件。

## 提交前检查

```bash
npm ci --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
(cd backend && mvn -B -ntp clean verify)
python3 scripts/check_public_release.py
git diff --check
```

后端数据库集成测试需要 Docker；DEV 储值专项需要独立数据库和显式开关，详见[验证说明](docs/verification.md)。在 PR 中写明实际执行的命令、结果和跳过项。

## 改动边界

- Flyway V1–V36 是已发布基线，不改写旧 SQL；数据库变更追加迁移。
- 金额、权限、预约状态机、资源锁和 DEV 资金边界的变更需给出场景、反例与回归证据。
- 每个 PR 尽量只解决一个问题，附上触发方式、变化后的行为和验证。
- 保留第三方版权和授权说明。
- 历史报告不能作为新代码通过验证的依据；新增实验输出默认留在忽略目录，先审查再精选公开。

## 维护范围

维护者会根据可复现性与项目范围处理 Issue。当前没有生产支持或响应时间承诺。与商业资金、生产多实例部署有关的提案需单独讨论。
