# 验证方法

## 前端与默认后端

```bash
npm ci --prefix frontend
npm test --prefix frontend
npm run build --prefix frontend
(cd backend && mvn -B -ntp clean verify)
```

后端 MySQL/Redis Testcontainers 测试需要可用 Docker。Surefire 为测试 JVM 固定 `Asia/Shanghai`，与项目的日历和集成夹具保持一致；不改变部署进程的时区。完整容量实验另用 `mvn -B -ntp clean '-Dtest=*Test,WaveAScalePerformanceIT' package`，本地性能阈值不适合作为所有共享 CI runner 的生产性能保证。

## 显式 DEV 储值集成

四组测试默认由系统属性控制，常规命令可能显示跳过。运行器创建四个独立空 schema，再依次开启各自开关，失败或跳过均返回非零退出码。

```bash
python3 scripts/run_stored_value_integrations.py
```

脚本使用新的带标签 MySQL 容器、随机密码和环回端口；不会连接默认持久数据库。MySQL、JDBC 与测试 JVM 均使用 `Asia/Shanghai`，避免 UTC CI 主机与数据库的时间比较不一致。结果写入 `.run/<运行名>/`；默认运行名带随机后缀。仅清理自己创建的容器，保留测试报告。

## 启停脚本

```bash
for test in scripts/*.test.sh; do bash "$test"; done
```

## 发布检查与依赖

```bash
python3 scripts/check_public_release.py
git diff --check
npm audit --prefix frontend --registry=https://registry.npmjs.org
npm audit --prefix engineering/lab --registry=https://registry.npmjs.org
python3 scripts/audit_maven_dependencies.py
gitleaks git --config .gitleaks.toml --redact
```

Maven 审计先解析实际传递依赖，再查询 OSV。网络不可用或服务失败会明确报错，不会伪报零漏洞。审计也包含 test scope；命中公告需要结合使用路径判断影响。

当前两条 Spring MVC 公告的适用性判断、源码条件检查与复核期限见[依赖公告审查](security-advisories.md)；原始命中仍保留在审计报告中。

Gitleaks 默认规则保持开启，仅豁免精选 C2 报告中的特定合成请求键、固定格式的迁移文件 SHA256，以及一条固定的日志脱敏测试 JWT。

## 可靠性实验

参见 [engineering/lab/README](../engineering/lab/README.md)。每次新建运行名，使用独占合成环境，实验运行记录默认不提交。历史报告不能替代当前代码回归。

本地实际运行结果见[发布前验证记录](release-verification.md)。GitHub CI 的实际状态见 [Actions](https://github.com/JacyyC/shiguang-venue-platform/actions/workflows/ci.yml)，首次运行问题与修复见[发布资料](github-release.md)。本地通过不能代替远程检查。
