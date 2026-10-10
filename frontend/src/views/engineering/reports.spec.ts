import { describe,it,expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { parseReport, type Source } from './reports'
const sources: Source[] = JSON.parse(readFileSync('public/engineering/manifest.json','utf8'))
const load = (source: Source) => JSON.parse(readFileSync(`public/engineering/${source.case}.json`,'utf8'))
describe('engineering archived evidence',()=>{
  for (const s of sources) it(`${s.case} derives actual rows and rejects failed, empty and wrong-version reports`,()=>{
    const d=load(s);expect(parseReport(d,s).rows.length).toBeGreaterThan(0)
    expect(()=>parseReport({...d,passed:false},s)).toThrow()
    expect(()=>parseReport({...d,results:[]},s)).toThrow()
    expect(()=>parseReport({...d,head:'stale'},s)).toThrow()
  })
  it('rejects successful HTTP with missing durable outbox facts',()=>{
    const s=sources[1],d=load(s);d.results.find((r:any)=>r.phase==='formal').data[0].outboxFacts--
    expect(()=>parseReport(d,s)).toThrow('持久事实')
  })
  it('rejects an incomplete browser set and absent target transaction window',()=>{
    const s=sources[0],d=load(s);d.results.pop();expect(()=>parseReport(d,s)).toThrow()
    const other=load(s);other.results[0].browser.events=[];expect(()=>parseReport(other,s)).toThrow('窗口')
  })
  it('rejects skipped conditional suites and nonzero reconciliation differences',()=>{
    const s=sources[2],d=load(s);d.results[0].skipped=1;expect(()=>parseReport(d,s)).toThrow()
    const other=load(s);other.results.at(-1).actual.reconciliation.differenceCount=1;expect(()=>parseReport(other,s)).toThrow()
  })
})
