import {chromium,firefox,webkit} from 'playwright';
import fs from 'node:fs';
const [base,out]=process.argv.slice(2);if(!base?.startsWith('http://127.0.0.1:')||!out)throw Error('explicit local URL and output required');fs.mkdirSync(out,{recursive:true});
const checks=[];
for(const [name,engine] of Object.entries({chromium,firefox,webkit})){
 const b=await engine.launch();const p=await b.newPage({viewport:{width:1440,height:1000},reducedMotion:'reduce'});const errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.goto(base+'/engineering');await p.getByText('3 / 3',{exact:true}).waitFor();await p.locator('#C2 .metric').waitFor();await p.locator('#C3 .metric').waitFor();
 for(const width of [1440,390])for(const theme of ['light','dark']){
  await p.setViewportSize({width,height:1000});const current=await p.locator('.engineering').getAttribute('data-theme');if(current!==theme)await p.getByRole('button',{name:theme==='dark'?'切换深色':'切换浅色'}).click();
  if(await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('horizontal overflow');
  if(name==='chromium')await p.screenshot({path:`${out}/${width}-${theme}.png`,fullPage:true});
  checks.push({engine:name,width,theme,overflow:false});
 }
 await p.setViewportSize({width:1440,height:1000});await p.locator('body').click({position:{x:5,y:5}});await p.keyboard.press('Control+Home');
 await p.locator('.skip').focus();await p.keyboard.press('Enter');if(await p.evaluate(()=>document.activeElement?.id)!=='engineering-main')throw Error('skip target focus');
 await p.keyboard.press('Tab');const summary=p.locator('#C1 details summary').first();await summary.focus();await p.keyboard.press('Enter');if(!await p.locator('#C1 .trace').isVisible())throw Error('keyboard disclosure');
 const focus=await summary.evaluate(e=>getComputedStyle(e).outlineStyle);if(focus==='none')throw Error('missing focus ring');
 await p.route('**/engineering/C2.json',r=>r.fulfill({status:404,body:'missing'}));await p.reload();await p.locator('#C2 [role=alert]').waitFor();if(await p.locator('#C2 .metric').count())throw Error('missing report falsely green');await p.unroute('**/engineering/C2.json');await p.getByRole('button',{name:'重新读取材料'}).click();await p.locator('#C2 .metric').waitFor();
 if(errors.length)throw Error(errors.join('\n'));checks.push({engine:name,keyboard:true,focus,missingReport:true,retry:true,pageErrors:errors});await b.close();
}
fs.writeFileSync(`${out}/checks.json`,JSON.stringify({passed:true,checks},null,2)+'\n');console.log(JSON.stringify({passed:true,checks},null,2));
