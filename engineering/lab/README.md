# 拾光本地可靠性实验

本工具只面向独占合成环境，故障注入类位于后端test classpath，不打入正式jar。普通产品界面没有实验执行权限或命令入口。来源：前项s02/run.py的全新输出/失败传播与s05/stored-integrations.py的显式条件集成核验；本目录独立维护。

## 安装与C1复现

仓库根目录要求Java17、Maven3.9+、Node.js 24 LTS、Python3.9+、Docker与本机lsof。安装产品前端 `npm ci --prefix frontend`，再在本目录运行 `npm ci` 和 `npx playwright install chromium firefox webkit`。Playwright固定1.58.2及lock；MySQL8.4和Redis7镜像在lab.py固定digest，首次启动可能下载。Maven允许使用正常本地缓存，无私有浏览器路径。

从仓库根执行（RUN是未使用过的sgrel-前缀名称）：

```bash
LAB=engineering/lab
python3 "$LAB/lab.py" start --run sgrel-example-01
python3 "$LAB/lab.py" seed --run sgrel-example-01
python3 "$LAB/run.py" c1 --run sgrel-example-01
python3 "$LAB/test_runner.py"
```

start仅创建新环境，已有目录拒绝覆盖；占用端口拒绝复用。凭据和进程PID在忽略目录`.run/<RUN>/env.json`（0600）；不提交、不复制给浏览器报告。浏览器进程通过stdin接收临时配置。SQL只连接标签和容器ID验证后的本次库；真实HTTP使用合成身份。服务默认外部邮件/支付宝沙箱/观测Provider调度关闭。后端测试配置另外要求显式profile、环回绑定与随机控制secret。

结果保存于`engineering/runs/<RUN>/`；各场景目录拒绝覆盖，失败不会被绿色汇总掩盖。c1.py为真实MySQL/Redis/HTTP和三内核未提交窗口，c1-controls.py补无请求、回滚、提交后核对/重放、异载荷、用户隔离与真实资源冲突。恢复测试的mock调用次数和此处真实事件/通知落库分开。测试屏障45秒自动失败；报告记录注入到达、事务活跃、连接内可见和独立查询NONE。

运行资源由本次run独占；不要依据端口或历史PID清理其他任务。恢复先核对env容器ID与`lab.owner/lab.run`标签；进程还需核对当前监听PID、进程组、cwd和命令。工具不自动删库或卷；失败目录保留，修复工具后使用新的run或明确的新场景目录。

## 边界

客户端结果查询NONE只代表当前视图不可见，保留原键重试；联系人仍需用户在刷新后重新填写，未存储个人信息副本。真实数据库同键唯一约束串行化请求，异载荷返回既有原因码。C1不证明生产多实例/Redis故障可用性、真实移动设备或外部消息投递；站内notice/outbox为真实落库，外部delivery为DEV mock。

## C2 资源竞争与测量

启动并seed新的环境后运行 `python3 "$LAB/run.py" c2 --run sgrel-example-02`。负载条件固定于[load-contract.json](load-contract.json)，正式测量前修改并保存合同；运行器核对启动配置匹配，三模式正确性加2组预热与8组正式负载必须全部出现。每轮独立场馆/资源，jsonl保留每个HTTP结果，可重算百分位；report保存SQL关联和资源采样。限流在本地实验显式设为1000000/分钟（产品默认90），不表示默认公网吞吐；热点拒绝含Redis协调409，不能把冲突吞吐当成功吞吐。

`python3 "$LAB/lab.py" stop --run sgrel-example-02`先验证容器ID/标签及当前监听进程cwd/命令、后端secret响应，再停止进程组和容器；不删除数据。启动JVM上限1GiB、连接池10、Tomcat线程200，配置随环境记录；其它用户进程不操作。

## C3 DEV 账务

新的环境启动并seed后执行 `python3 "$LAB/run.py" c3 --run sgrel-example-03`。工具为四个显式条件集成创建独占空schema，顺序执行Maven并核对新XML、零跳过和36条迁移checksum；不要在同一checkout并发运行Maven编译。连接参数由本次run内0600属性文件传给Surefire，不输出凭据。随后通过真实应用完成DEV充值、预约审核、支付重放、原桶退款和全量对账；报告保留订单/支付/退款/分配关联与每阶段分录投影。

订单集成的本地结算协作者为mock，故障注入证明真实MySQL事务回滚；真实HTTP案例另用实际结算服务补足边界。过期赠送退款和0.01差异由测试夹具构造，不能解释为真实时间流逝或真实渠道故障。详见[账务报告](../README.md#c3-dev-账务)。运行完按上述stop命令核验并停止，不删除数据。

## 展示材料

`python3 "$LAB/publish-reports.py"`从显式归档生成前端静态材料；不触发实验。访问`/engineering`查看。`node "$LAB/showcase-check.mjs" http://127.0.0.1:<前端端口> <新证据目录>`验证三内核、明暗窄屏和关键交互，调用前先用lab身份检查当前服务。报告来源及更新限制见[展示说明](../README.md)。
