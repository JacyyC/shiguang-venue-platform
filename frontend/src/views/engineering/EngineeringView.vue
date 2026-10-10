<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { loadReport, type Evidence, type Source, type CaseId } from './reports'
const theme = useThemeStore()
const sources = ref<Source[]>([])
const results = ref<Partial<Record<CaseId,Evidence>>>({})
const errors = ref<Partial<Record<CaseId,string>>>({})
const globalError = ref('')
const cases: {id:CaseId; title:string; question:string; problem:string; mechanism:string[]; tradeoff:string; scope:string; code:string}[] = [
  {id:'C1',title:'让一次预约，始终是一次。',question:'网络失败后，原请求去了哪里？',problem:'真实未提交事务已写入请求身份，但独立查询返回 NONE。旧客户端清除请求键，换键重试后产生两笔预约。',mechanism:['保留未决请求键','同键重试','唯一约束串行化','重放同一预约'],tradeoff:'复用既有事务与唯一键，修正客户端对 NONE 的解释。无需增加请求状态或独立事务；代价是用户仍需核对未决结果。',scope:'测试类路径屏障固定未提交窗口；浏览器网络故障由工具注入。三内核及HTTP邻接控制均为本地合成数据；刷新后联系人需重新填写。站内事件真实落库，外部投递为DEV mock。',code:'frontend/src/stores/reservation.ts · backend/src/main/java/com/example/venue/reservation/service/ReservationService.java'},
  {id:'C2',title:'把竞争，留在正确的范围。',question:'不同场地，为何也会彼此阻塞？',problem:'真实负载发现过宽锁扫描、缺失键间隙锁和提交后嵌套借连接，导致无关资源阻塞或成功业务缺少归因事实。独立复现进一步校正同馆锁范围和首次规则创建。',mechanism:['资源时间索引','场馆共享 / 场地排他','业务与最小事实同事务','提交后异步归因'],tradeoff:'复用既有索引、锁和唯一键。最小outbox随源事务提交，写失败会回滚业务；归因失败不回滚已提交业务，队列拒绝保留待处理事实。未新增迁移或扩大连接池；HTTP与持久化同时对账。',scope:'M4共机闭环负载；热点与跨场馆分散各1/8并发、20秒×2。限流显式覆盖为1000000/分钟。吞吐含排空，p95含所有响应，预热排除。无每请求DB等待指标；不代表生产SLA、Redis宕机或多实例能力。',code:'backend/src/main/java/com/example/venue/reservation/mapper/ReservationOccupancyMapper.java · backend/src/main/java/com/example/venue/advertising/service/AdvertisingConversionService.java'},
  {id:'C3',title:'每一笔变化，都能回到账上。',question:'重放与退款，是否仍然守恒？',problem:'仅看余额恢复不足以证明正确：必须关联订单支付、分配批次、借贷分录及各个投影桶，并让故障真正发生在事务中。',mechanism:['订单锁与幂等键','同事务记账结算','按原分配退款','分录与投影对账'],tradeoff:'沿用既有DEV账务、审批与补偿合同，无资金实现修改。四组集成负责并发、回滚、过期赠送与0.01差异反例；真实应用补足结算关联。',scope:'仅合成DEV资金。订单集成mock本地结算协作者以注入失败，真实HTTP另用实际服务。过期与差异为测试夹具，不能视为真实时间流逝或真实支付渠道。',code:'backend/src/test/java/com/example/venue/storedvalue/StoredValueOrderPaymentMySqlIntegrationTest.java · engineering/lab/c3.py'}
]
async function load() {
  globalError.value = ''; errors.value = {}; results.value = {}
  try {
    const r = await fetch('/engineering/manifest.json')
    if (!r.ok) throw new Error(`材料索引不可用（HTTP ${r.status}）`)
    const data: Source[] = await r.json()
    if (!Array.isArray(data) || data.length !== 3 || !cases.every(c => data.filter(s=>s.case===c.id && s.url===`/engineering/${c.id}.json`).length===1)) throw new Error('材料索引不完整')
    sources.value = data
    await Promise.all(data.map(async s => {
      try { results.value[s.case] = await loadReport(s) }
      catch (e) { errors.value[s.case] = e instanceof Error ? e.message : '报告读取失败' }
    }))
  } catch(e) { globalError.value = e instanceof Error ? e.message : '材料读取失败' }
}
onMounted(()=>{theme.init();void load()})
const source = (id:CaseId) => sources.value.find(s=>s.case===id)
</script>

<template>
  <div class="engineering" :data-theme="theme.theme">
    <a class="skip" href="#engineering-main">跳到案例正文</a>
    <header><RouterLink class="brand" to="/">拾光<span>·</span></RouterLink><span class="edition">工程手记 / 01</span><button type="button" @click="theme.toggle()">{{ theme.isDark ? '切换浅色' : '切换深色' }}</button></header>
    <main id="engineering-main" tabindex="-1" aria-labelledby="engineering-title">
      <section class="intro"><p class="eyebrow">SHIGUANG / ENGINEERING NOTES</p><h1 id="engineering-title">每一次确认，<br>都有依据。</h1><div class="intro-bottom"><p>从失败反例，到可重放的工程证据。<br>三个核心链路，记录问题如何被发现、修复与验证。</p><span class="archive">隔离实验 · 实测归档<br>非实时监控 / 非生产承诺</span></div></section>
      <nav aria-label="案例导航"><a v-for="(c,i) in cases" :key="c.id" :href="`#${c.id}`"><span>0{{ i+1 }}</span>{{ ['预约恢复','资源并发','DEV账务'][i] }}<span aria-hidden="true">↗</span></a></nav>
      <div v-if="globalError" class="error" role="alert">{{ globalError }} <button @click="load">重新读取材料</button></div>
      <article v-for="(c,i) in cases" :id="c.id" :key="c.id" :aria-labelledby="`${c.id}-title`">
        <div class="case-heading"><div><p class="eyebrow">CASE 0{{ i+1 }} / {{ c.question }}</p><h2 :id="`${c.id}-title`">{{ c.title }}</h2></div><div v-if="results[c.id]" class="metric"><strong>{{ results[c.id]!.metric }}</strong><span>{{ results[c.id]!.caption }}</span></div></div>
        <p class="problem">{{ c.problem }}</p>
        <div class="mechanism"><p class="eyebrow">机制示意 · 非运行动画</p><ol><li v-for="step in c.mechanism" :key="step">{{ step }}</li></ol></div>
        <div v-if="errors[c.id]" class="error" role="alert">{{ errors[c.id] }}。此案例未获得可展示的通过结果。<button @click="load">重新读取材料</button></div>
        <p v-else-if="!results[c.id] && !globalError" role="status">正在读取实测报告…</p>
        <template v-if="results[c.id]">
          <div class="data-heading"><h3>实测记录</h3><span>{{ results[c.id]!.run }} · {{ results[c.id]!.utc.slice(0,10) }}</span></div>
          <div class="table-wrap" tabindex="0" :aria-label="`${c.id}实测数据表，可横向滚动`"><table><caption>{{ results[c.id]!.caption }}（原始归档）</caption><thead><tr><th v-for="col in results[c.id]!.columns" :key="col" scope="col">{{ col }}</th></tr></thead><tbody><tr v-for="row in results[c.id]!.rows" :key="row.label"><th scope="row">{{ row.label }}</th><td v-for="(value,j) in row.values" :key="j">{{ value }}</td></tr></tbody></table></div>
          <details><summary>{{ c.id==='C1' ? '展开真实时间线（Chromium，工具相对时钟）' : c.id==='C2' ? '展开测量条件' : '展开实际对象关联' }}</summary><ol class="trace"><li v-for="line in results[c.id]!.timeline" :key="line">{{ line }}</li></ol></details>
        </template>
        <div class="notes"><section><h3>为什么这样做</h3><p>{{ c.tradeoff }}</p></section><section><h3>证据能说明什么</h3><p>{{ c.scope }}</p></section></div>
        <details class="reproduce"><summary>复现命令与来源</summary><p>仓库根目录执行；先安装前后端依赖并启动Docker。每个案例使用新的运行名，完整安装说明见engineering/lab/README.md。</p><pre><code>LAB=engineering/lab
npm ci --prefix "$LAB"
(cd "$LAB" && npx playwright install chromium firefox webkit)
python3 "$LAB/lab.py" start --run sgrel-new-{{ c.id.toLowerCase() }}
python3 "$LAB/lab.py" seed --run sgrel-new-{{ c.id.toLowerCase() }}
python3 "$LAB/run.py" {{ c.id.toLowerCase() }} --run sgrel-new-{{ c.id.toLowerCase() }}
python3 "$LAB/lab.py" stop --run sgrel-new-{{ c.id.toLowerCase() }}</code></pre><p>代码入口：{{ c.code }}</p><p v-if="source(c.id)">报告源：{{ source(c.id)!.source }}</p><template v-if="results[c.id]"><p>被测基线：{{ results[c.id]!.head }}<br>产品差异 SHA256：{{ results[c.id]!.diff }}<br>源码清单 SHA256：{{ results[c.id]!.manifest }}</p><p>此页显示历史实测版本。当前构建未被自动认定为该版本；源码变化后应重新运行。</p></template><a v-if="source(c.id)" :href="source(c.id)!.url" download>下载原始报告 JSON</a></details>
      </article>
      <footer><p>证据保留失败，工程说明边界。</p><p>本页只读取脱敏静态报告。完整反例、原始采样与交接见engineering/README.md。</p><a href="#engineering-title">回到开头 ↑</a></footer>
    </main>
  </div>
</template>

<style scoped>
.engineering{--paper:var(--sg-engineering-canvas);--surface:var(--sg-engineering-surface);--ink:var(--sg-engineering-ink);--muted:var(--sg-engineering-muted);--line:var(--sg-engineering-line);--soft:var(--sg-engineering-soft);--accent:var(--sg-engineering-accent);background:var(--paper);color:var(--ink);font:16px/1.75 var(--sg-font-sans);position:relative;min-height:100vh;padding:0 max(24px,calc((100vw - 1160px)/2));isolation:isolate}

*{box-sizing:border-box}
a{color:inherit;text-underline-offset:5px}
button{font:inherit;color:inherit;background:transparent;border:1px solid var(--line);border-radius:var(--sg-radius-control);padding:9px 16px;min-height:44px;cursor:pointer}
button:hover,a:hover{color:var(--accent)}
:focus-visible{outline:2px solid var(--accent);outline-offset:5px}
.skip{position:absolute;top:0;left:24px;transform:translateY(-120%);z-index:3;background:var(--surface);padding:12px}
.skip:focus{transform:none}
header{height:100px;display:flex;gap:24px;align-items:center;border-bottom:1px solid var(--line)}
.brand{font-size:29px;font-weight:800;text-decoration:none}
.brand span{color:var(--accent)}
.edition{color:var(--muted);font-size:14px;margin-right:auto}
h1,h2,h3,p{margin:0}
h1{font-size:clamp(40px,6vw,76px);line-height:1.16;letter-spacing:-2px;margin:22px 0 32px;font-weight:750}
h2{font-size:clamp(26px,3vw,37px);line-height:1.4;margin:12px 0}
h3{font-size:18px}
.intro{padding:65px 0 42px}
.eyebrow{font-size:13px;color:var(--muted);letter-spacing:1px}
.intro-bottom{display:flex;justify-content:space-between;align-items:end;gap:25px}
.archive{font-size:13px;color:var(--muted);border-left:2px solid var(--accent);padding-left:18px}
nav{display:grid;grid-template-columns:repeat(3,1fr);gap:30px;margin-bottom:50px}
nav a{display:flex;gap:18px;align-items:center;border-top:2px solid var(--ink);padding:18px 0;text-decoration:none;min-height:60px}
nav a span:first-child{color:var(--accent);font-size:13px}
nav a span:last-child{margin-left:auto}
article{padding:40px 0 60px;border-top:1px solid var(--line);scroll-margin-top:24px}
.case-heading{display:flex;justify-content:space-between;gap:32px;align-items:center}
.metric{display:flex;flex-direction:column;text-align:right;min-width:170px}
.metric strong{font-size:42px;line-height:1.2;font-weight:500;color:var(--accent);font-variant-numeric:tabular-nums}
.metric span{font-size:13px;color:var(--muted);margin-top:8px}
.problem{max-width:830px;margin:15px 0 26px}
.mechanism{background:var(--soft);padding:22px 28px;margin-bottom:32px}
.mechanism ol{display:grid;grid-template-columns:repeat(4,1fr);gap:24px;padding-left:20px;margin-bottom:0}
.mechanism li::marker{color:var(--accent)}
.data-heading{display:flex;justify-content:space-between;gap:16px;flex-wrap:wrap;align-items:center;margin:20px 0 10px}
.data-heading span{font-size:13px;color:var(--muted)}
.table-wrap{overflow-x:auto;border:1px solid var(--line)}
table{width:100%;border-collapse:collapse;text-align:left;font-variant-numeric:tabular-nums}
caption{text-align:left;font-size:13px;padding:10px 18px;color:var(--muted)}
th,td{padding:13px 18px;border-top:1px solid var(--line);white-space:nowrap}
thead{background:var(--soft)}
tbody th{font-weight:500}
summary{cursor:pointer;min-height:44px;padding:12px 0;color:var(--accent)}
details{margin:8px 0}
details p{margin:16px 0;overflow-wrap:anywhere}
.trace{font-family:var(--sg-font-mono);font-size:13px}
.notes{display:grid;grid-template-columns:1fr 1fr;gap:48px;margin:26px 0}
.notes p{color:var(--muted);margin-top:8px;font-size:14px}
.reproduce{border-top:1px solid var(--line);font-size:14px}
pre{padding:20px;background:var(--soft);white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.8}
.error{border-left:3px solid var(--accent);padding:16px;background:var(--soft)}
.error button{margin:8px}
footer{border-top:2px solid var(--ink);padding:32px 0 60px}
footer p:first-child{font-size:22px}
footer p+ p{font-size:13px;color:var(--muted);margin:12px 0}
footer a{display:inline-block;padding:10px 0;min-height:44px}

@media(max-width:650px){header{height:80px;gap:14px}
.edition{font-size:13px}
header button{font-size:13px;padding:8px}
.intro{padding-top:42px}
.intro-bottom{display:block}
.archive{display:block;margin-top:26px}
nav{grid-template-columns:1fr;gap:0;margin:0 0 22px}
nav a{border-top:1px solid var(--line);padding:13px 0}
.case-heading{display:block}
.metric{text-align:left;margin:16px 0}
.metric strong{font-size:36px}
.mechanism{padding:18px}
.mechanism ol{grid-template-columns:1fr 1fr;gap:20px;font-size:14px}
.notes{grid-template-columns:1fr;gap:24px}
.trace{padding-left:20px;overflow-wrap:anywhere}
th,td{padding:12px}
article{padding-top:28px}
}

@media(prefers-reduced-motion:reduce){*{scroll-behavior:auto!important;animation:none!important;transition:none!important}
}

</style>
