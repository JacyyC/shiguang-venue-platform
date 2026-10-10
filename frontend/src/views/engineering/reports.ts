export type CaseId = 'C1' | 'C2' | 'C3'
export interface Source { case: CaseId; url: string; run: string; head: string; sha256: string; source: string }
export interface Row { label: string; values: string[] }
export interface Evidence { run: string; head: string; utc: string; diff: string; manifest: string; rows: Row[]; columns: string[]; metric: string; caption: string; timeline: string[] }
const fail = (message: string): never => { throw new Error(message) }
const number = (x: unknown): number => typeof x === 'number' && Number.isFinite(x) && x >= 0 ? x : fail('数值缺失或无效')
// The report is external data. All fields used for a positive conclusion are checked here.
export function parseReport(raw: unknown, source: Source): Evidence {
  const d = raw as any
  if (!d || d.case !== source.case || d.run !== source.run || d.head !== source.head) fail('报告身份或版本不匹配')
  if (d.passed !== true) fail('此报告记录失败，不能展示为通过')
  if (!Array.isArray(d.results) || !d.results.length || d.results.some((r: any) => r.passed !== true)) fail('场景缺失或断言失败')
  if (!d.utc || !d.productDiffSha256 || !d.sourceManifestSha256) fail('版本来源不完整')
  const result: Evidence = { run: d.run, head: d.head, utc: d.utc, diff: d.productDiffSha256, manifest: d.sourceManifestSha256, rows: [], columns: [], metric: '', caption: '', timeline: [] }
  if (source.case === 'C1') {
    if (d.results.length !== 3 || new Set(d.results.map((r: any) => r.engine)).size !== 3 || !['chromium','firefox','webkit'].every(e => d.results.some((r: any) => r.engine === e))) fail('三内核证据不完整')
    result.columns = ['内核', '预约', '占用', '状态日志']
    result.rows = d.results.map((r: any) => {
      if (r.exit !== 0 || !r.browser?.sameKey || !r.counts || !['reservations','commands','occupancies','businessEvents','funnelEvents','ownerNotices','statusLogs'].every(k => r.counts[k] === 1)) fail('身份恢复或副作用不变量未通过')
      if (!r.browser.events.some((e: any) => e.stage === 'uncommitted' && e.transactionActive && e.ownConnectionRows === 1) || !r.browser.events.some((e: any) => e.stage === 'independent-read' && e.result === 'NONE')) fail('未提交目标窗口缺失')
      return { label: r.engine, values: ['1','1','1'] }
    })
    result.metric = '3 / 3'; result.caption = '浏览器内核 · 同键恢复'
    result.timeline = d.results[0].browser.events.map((e: any) => `${number(e.ms)} ms · ${e.stage}`)
  } else if (source.case === 'C2') {
    const required = ['EXCLUSIVE','CAPACITY','ASSIGNED_SEAT',...['hotspot','distributed'].flatMap(s => [`warmup-${s}-2-0`,...[1,8].flatMap(n => [1,2].map(r => `formal-${s}-${n}-${r}`))])]
    if (d.smokeOnly !== false || d.results.length !== 13 || !required.every(k => d.results.some((r: any) => r.scenario === k))) fail('正式测量或三模式缺失')
    const rounds = d.results.filter((r: any) => r.phase === 'formal')
    if (rounds.length !== 8) fail('正式轮次缺失')
    result.columns = ['形态 / 并发 / 轮次', '成功 / 冲突', '成功/秒', 'p95 ms']
    result.rows = rounds.map((r: any) => {
      const c = r.counts
      if (!c || c.timeout !== 0 || c.unexpected !== 0 || c.rateLimit !== 0 || number(c.success) + number(c.conflict) !== number(r.samples)) fail('请求分类或错误阈值未通过')
      if (!Array.isArray(r.data) || !r.data.length || r.data.some((x: any) => !['occupancies','quantity','commands','outboxFacts','outboxReservations'].every(k => x[k] === x.reservations)) || r.data.reduce((sum: number,x: any) => sum + number(x.reservations),0) !== c.success || r.identityRows !== c.success) fail('HTTP成功与持久事实不一致')
      return { label: `${r.shape === 'hotspot' ? '热点' : '分散'} / ${r.concurrency} / ${r.repeat}`, values: [`${c.success} / ${c.conflict}`,number(r.successPerSecond).toFixed(2),number(r.p95ms).toFixed(2)] }
    })
    result.metric = String(rounds.reduce((sum: number,r: any) => sum + r.samples,0)); result.caption = '正式请求 · 含预期竞争拒绝'
    result.timeline = [`${d.contract.hardware.cpu} · ${d.contract.hardware.logicalCores}核 · ${(d.contract.hardware.ramBytes/2**30).toFixed(0)} GiB`, `预热 ${d.contract.warmupSeconds}s；每轮 ${d.contract.durationSeconds}s × ${d.contract.repeats}次`, `JVM ${d.contract.application.jvmMaxHeap}；连接池 ${d.contract.application.hikariMaximumPoolSize}`, `本地限流覆盖 ${d.contract.application.rateLimitPerMinute}/分钟（默认90）`]
  } else {
    const names = ['StoredValueMySqlIntegrationTest','StoredValueOrderPaymentMySqlIntegrationTest','StoredValueApprovalMySqlIntegrationTest','StoredValueReconciliationMySqlIntegrationTest']
    const suites = names.map(n => d.results.find((r: any) => r.scenario === n))
    if (d.results.length !== 5 || suites.some(r => !r || r.exit !== 0 || number(r.tests) === 0 || r.failures !== 0 || r.errors !== 0 || r.skipped !== 0)) fail('账务集成缺失、失败或跳过')
    const a = d.results.find((r: any) => r.scenario === 'real-application-dev-settlement-refund')?.actual
    if (!a || !a.samePaymentReplay || a.repeatRefundHttp !== 400 || a.finalOrderStatus !== 'REFUNDED' || a.activeOccupancy !== 0 || a.reconciliation?.differenceCount !== 0) fail('真实结算/退款/对账未通过')
    result.columns = ['阶段', '本金', '赠送', '不平衡事务']
    result.rows = ['before','afterPayment','afterRefund'].map((k,i) => {
      const s = a[k]; if (s?.unbalancedTransactions !== 0) fail('分录不平衡')
      return { label: ['充值后','支付后','退款后'][i], values: [number(s.account.principal).toFixed(2),number(s.account.bonus).toFixed(2),'0'] }
    })
    if (a.before.account.principal !== a.afterRefund.account.principal || a.before.account.bonus !== a.afterRefund.account.bonus) fail('退款未回原桶')
    result.metric = String(suites.reduce((s,r) => s+r.tests,0)); result.caption = 'MySQL集成用例 · 零跳过'
    result.timeline = [`预约 ${a.reservationId} → 订单 ${a.orderId}`, `储值支付 ${a.payment.paymentId} · ${a.payment.paymentNo}`, `退款 ${a.refundRecordId} → 原分配批次`, `DEV对账 ${a.reconciliation.status} · 差异 ${a.reconciliation.differenceCount}`]
  }
  return result
}
export async function loadReport(source: Source, fetcher: typeof fetch = fetch): Promise<Evidence> {
  const response = await fetcher(source.url)
  if (!response.ok) fail(`报告不可用（HTTP ${response.status}）`)
  const text = await response.text()
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text))),b=>b.toString(16).padStart(2,'0')).join('')
  if (digest !== source.sha256) fail('报告内容与归档摘要不一致，请重新生成展示材料')
  return parseReport(JSON.parse(text),source)
}
