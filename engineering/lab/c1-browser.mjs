import {chromium,firefox,webkit} from 'playwright';
import fs from 'node:fs';
const cfg=JSON.parse(fs.readFileSync(0,'utf8'));const {env:e,users,fixture:f}=cfg;
const browser=await ({chromium,firefox,webkit}[cfg.engine||'chromium']).launch({headless:true});
const context=await browser.newContext({reducedMotion:'reduce',viewport:{width:1280,height:900}});const page=await context.newPage();page.setDefaultTimeout(15000);
const events=[];const record=(stage,data={})=>events.push({stage,ms:Math.round(performance.now()),...data});
async function control(action,body){const r=await fetch(e.base+'/__lab/'+action,{method:body?'POST':'GET',headers:{'Content-Type':'application/json','X-Lab-Secret':e.labSecret},body:body?JSON.stringify(body):undefined});if(!r.ok)throw Error('control '+r.status);return r.json()}
const token=users.owner.token;
const post=(payload,key)=>fetch(e.base+'/api/reservations',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token,'Idempotency-Key':key},body:JSON.stringify(payload)}).then(async r=>({status:r.status,body:await r.json()}));
let original,firstKey,retryKey,first=true;
try{
 await page.goto(e.front+'/login');
 await page.evaluate(({auth,f})=>{for(const [k,v] of Object.entries(auth))localStorage.setItem(k,typeof v==='string'?v:JSON.stringify(v));sessionStorage.setItem('shiguang-booking-draft',JSON.stringify({slots:[{...f.payload,status:'AVAILABLE',label:'可约',price:40}],venueId:f.venue.id,venueName:f.venue.name,spaceId:f.space.id,spaceName:f.space.name,spaceType:'MEETING',participantCount:1,seatIds:[]}));}, {auth:users.owner,f});
 const availabilityReady=page.waitForResponse(r=>r.url().includes('/resource-availability')&&r.status()===200);
 await page.goto(e.front+'/reservation/confirm');await availabilityReady;await page.waitForTimeout(100);
 const fill=async()=>{await page.locator('[data-test="contact-name"]').fill(f.payload.contactName);await page.locator('[data-test="contact-phone"]').fill(f.payload.contactPhone)};await fill();
 await page.route('**/api/reservations',async route=>{
   if(route.request().method()!=='POST')return route.continue();
   const key=route.request().headers()['idempotency-key'];const payload=route.request().postDataJSON();
   if(first){first=false;firstKey=key;await control('arm',{key});original=post(payload,key);
     let observation;
     for(let i=0;i<100;i++){observation=await control('barrier');if(observation.arrived)break;await new Promise(r=>setTimeout(r,50))}
     if(!observation.arrived||!observation.transactionActive||observation.ownConnectionRows!==1)throw Error('barrier not valid');
     record('uncommitted',observation);await route.abort('failed');record('client-response-lost');
   }else{
     retryKey=key;record('retry-issued',{sameKey:key===firstKey});const retry=post(payload,key);
     // With a changed key the second request can complete while the first is parked before the resource lock.
     if(key!==firstKey){const response=await retry;record('retry-before-original-commit',{http:response.status,id:response.body.data?.id});await control('release',{});await route.fulfill({status:response.status,contentType:'application/json',body:JSON.stringify(response.body)})}
     else {await new Promise(r=>setTimeout(r,100));await control('release',{});const response=await retry;record('same-key-replay',{http:response.status,id:response.body.data?.id});await route.fulfill({status:response.status,contentType:'application/json',body:JSON.stringify(response.body)})}
   }
 });
 await page.locator('.booking-summary [data-test="summary-submit"]').click();await page.locator('[data-test="reconcile-submit"]').waitFor();
 const query=await fetch(e.base+'/api/reservations/submission-result?idempotencyKey='+encodeURIComponent(firstKey),{headers:{Authorization:'Bearer '+token,'Idempotency-Key':firstKey}});const q=await query.json();
 if(query.status!==200||q.data?.status!=='NONE')throw Error('target invisible window not hit');
 record('independent-read',{http:query.status,result:q.data?.status});
 await page.locator('[data-test="reconcile-submit"]').click();
 await page.waitForTimeout(150);record('after-reconcile',{keyRetained:await page.evaluate(k=>sessionStorage.getItem('shiguang-submit-intent')===k,firstKey)});
 const refreshed=page.waitForResponse(r=>r.url().includes('/resource-availability')&&r.status()===200);await page.reload();await refreshed;await page.waitForTimeout(100);await fill();record('after-refresh',{keyRetained:await page.evaluate(k=>sessionStorage.getItem('shiguang-submit-intent')===k,firstKey)});
 await page.waitForFunction(()=>{const b=document.querySelector('.booking-summary [data-test="summary-submit"]');return b&&!b.disabled});
 record('retry-ready',{fields:await page.locator('[data-test="contact-name"],[data-test="contact-phone"]').evaluateAll(xs=>xs.map(x=>x.value.length))});
 const retryButton=page.locator('[data-test="retry-submit"]');
 if(await retryButton.count())await retryButton.click();else await page.locator('.booking-summary [data-test="summary-submit"]').click();
 await page.waitForURL(/reservation\/result\//);const response=await original;record('original-completed',{http:response.status,id:response.body.data?.id});
 const result={engine:cfg.engine||'chromium',version:browser.version(),spaceId:f.space.id,events,sameKey:firstKey===retryKey,originalId:response.body.data?.id,finalPath:new URL(page.url()).pathname};
 fs.writeFileSync(cfg.output,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
 if(!result.sameKey)process.exitCode=1;
} catch(error){await page.screenshot({path:cfg.output+'.png',mask:[page.locator('input')]});record('ui-diagnostic',{text:await page.locator('.inline-error,.booking-summary,.form-error').allTextContents()});record('error',{message:String(error)});fs.writeFileSync(cfg.output,JSON.stringify({events,error:String(error)},null,2)+'\n');console.error(String(error));process.exitCode=1;
}finally{await control('release',{}).catch(()=>{});await browser.close()}
