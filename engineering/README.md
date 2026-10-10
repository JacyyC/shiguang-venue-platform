# 可靠性工程案例

三个案例围绕可重放请求、资源冲突和账务一致性。实验使用独占合成环境；测试控制类在后端 test classpath，不进入正式产品 jar。

## C1 预约恢复

问题：原事务尚未提交且响应丢失时，独立查询返回 `NONE`，如何避免客户端换键再建一笔预约？

实现：保留未决请求键；以用户 + 键 + 载荷指纹串行化创建；结果核对与同键重试共同恢复。

代码：`frontend/src/stores/reservation.ts`、`backend/src/main/java/com/example/venue/reservation/service/ReservationCreateIdempotencyService.java`。

## C2 资源并发

问题：数据库锁读如何仅覆盖相关资源？成功 HTTP 响应是否对应完整的预约、占用、请求身份和 outbox 事实？

实现：资源时间索引、场馆共享/场地排他锁、唯一键先插入后读取，以及源事务内最小持久事实。热点冲突吞吐与成功预约吞吐分别计量。

代码：`backend/src/main/java/com/example/venue/reservation/mapper/ReservationOccupancyMapper.java`、`backend/src/main/java/com/example/venue/resource/service/ResourceAllocationService.java`。

## C3 DEV 账务

问题：重复支付、异常回滚、原分配退款和差额对账时，借贷分录与各资金桶的投影是否一致？

实现：订单锁、幂等键、同事务记账、按原桶退款和显式 MySQL 集成测试。

代码：`backend/src/test/java/com/example/venue/storedvalue/StoredValueOrderPaymentMySqlIntegrationTest.java`、`lab/c3.py`。

## 运行与证据

按 [lab/README](lab/README.md)安装工具，每次使用新的 `sgrel-` 运行名。运行记录写入 `engineering/runs/`，默认不提交。

`reports/C1.json`、`reports/C2.json` 和 `reports/C3.json` 是 2026-09-19 前后固定源版本的精选历史报告，复制保持字节一致。来源基线为 `fe16256dd1e4c269f55ea6677bcc51b52cfc1542` 加当时报告记录的源码差异；详情以各 JSON 的 source manifest 与版本字段为准。

`python3 engineering/lab/publish-reports.py` 只把这三份历史报告复制到前端并写入校验摘要，不运行实验。`/engineering` 明确显示历史版本与环境限制。依赖升级后的当前验证另见[发布验证](../docs/release-verification.md)。

所有数字仅描述对应的本地合成环境，不表示生产容量、真实资金、多实例长稳或外部消息送达能力。
