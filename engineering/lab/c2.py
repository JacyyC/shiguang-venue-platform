"""Real HTTP competition and bounded closed-loop load; no synthetic success states."""
import argparse,collections,concurrent.futures,hashlib,json,math,socket,threading,time,uuid
import lab as L
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--case',default='c2');p.add_argument('--smoke',action='store_true');a=p.parse_args()
e=L.load(a.run);lock=L.exclusive(e);listeners=L.listeners(e);out=L.output(a.run,a.case)
u=json.loads((L.ROOT/'.run'/a.run/'fixture.json').read_text())['users'];token=u['owner']['token'];rows=[]
contract=json.loads((L.HERE/'load-contract.json').read_text())
assert e['configuration']==contract['application'], 'configuration differs from frozen contract'
def post(payload):
    begin=time.perf_counter();key='c2-'+uuid.uuid4().hex
    try:
        status,r=L.request(e['base'],'/api/reservations',payload,token,{'Idempotency-Key':key})
        reason=(r.get('data') or {}).get('reasonCode')
        category='success' if status==200 and r.get('code')==0 else 'rateLimit' if status==429 else 'conflict' if status==409 and (reason in ['EXCLUSIVE_OVERLAP','CAPACITY_EXCEEDED','SEAT_ALREADY_ALLOCATED'] or r.get('message')=='该时段正在被其他用户提交，请稍后刷新') else 'unexpected'
        return {'ms':round((time.perf_counter()-begin)*1000,3),'category':category,'http':status,'reason':reason or ('REDIS_SUBMISSION_BUSY' if category=='conflict' else None),'id':(r.get('data') or {}).get('id'),'key':key,'spaceId':payload['spaceId']}
    except Exception as ex:return {'ms':round((time.perf_counter()-begin)*1000,3),'category':'timeout' if isinstance(ex,(TimeoutError,socket.timeout)) else 'unexpected','errorType':type(ex).__name__,'key':key,'spaceId':payload['spaceId']}
def db(sid):
    q=f'''select (select count(*) from reservation where space_id={sid}), (select count(*) from reservation_occupancy where space_id={sid} and active_flag=1), (select coalesce(sum(quantity),0) from reservation_occupancy where space_id={sid} and active_flag=1), (select count(*) from reservation_create_command where reservation_id in (select id from reservation where space_id={sid}));'''
    result=dict(zip(['reservations','occupancies','quantity','commands'],map(int,L.sql(e,q).split())))
    result['outboxFacts']=int(L.sql(e,f"select count(*) from advertising_conversion_callback where event_type='RESERVATION_SUBMITTED' and reservation_id in (select id from reservation where space_id={sid})"))
    result['outboxReservations']=int(L.sql(e,f"select count(distinct reservation_id) from advertising_conversion_callback where event_type='RESERVATION_SUBMITTED' and reservation_id in (select id from reservation where space_id={sid})"))
    return result
def command_count(samples):
    keys=[s['key'] for s in samples]
    total=0
    for start in range(0,len(keys),100):
        quoted=','.join("'"+k+"'" for k in keys[start:start+100])
        total+=int(L.sql(e,'select count(*) from reservation_create_command where idempotency_key in ('+quoted+')'))
    return total

def check(name,fn):
    try:actual=fn();rows.append({'scenario':name,'passed':True,'actual':actual})
    except Exception as ex:rows.append({'scenario':name,'passed':False,'error':str(ex)})
def race(mode):
    f=L.fixture(e,u['admin']['token'],mode=mode,capacity=3);payload=f['payload'];sid=f['space']['id'];initial=0
    if mode=='CAPACITY':assert post({**payload,'participantCount':2})['category']=='success';initial=2
    if mode=='ASSIGNED_SEAT':
        seats=list(map(int,L.sql(e,f'select id from venue_resource_seat where space_id={sid} order by id').split()));payload={**payload,'seatIds':[seats[0]]}
    barrier=threading.Barrier(2)
    def attempt():barrier.wait();return post(payload)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:responses=list(pool.map(lambda _:attempt(),range(2)))
    assert sum(r['category']=='success' for r in responses)==1,responses
    assert sum(r['category']=='conflict' for r in responses)==1,responses
    rejected=post(payload);expected={'EXCLUSIVE':'EXCLUSIVE_OVERLAP','CAPACITY':'CAPACITY_EXCEEDED','ASSIGNED_SEAT':'SEAT_ALREADY_ALLOCATED'}[mode]
    assert rejected['reason']==expected,rejected
    assert command_count(responses+[rejected])==1
    counts=db(sid);assert counts['quantity']==initial+1 and counts['outboxFacts']==counts['outboxReservations']==counts['commands']==counts['reservations']==(2 if initial else 1),counts
    extra=None
    if mode=='EXCLUSIVE':
        extra=post({**payload,'startTime':payload['endTime'],'endTime':payload['endTime'].replace('12:00:00','13:00:00')});assert extra['category']=='success'
    if mode=='ASSIGNED_SEAT':
        extra=post({**payload,'seatIds':[seats[1]]});assert extra['category']=='success'
        dup=L.sql(e,f'select count(*) from (select s.seat_id from reservation_occupancy_seat s join reservation_occupancy o on o.id=s.occupancy_id where o.space_id={sid} and o.active_flag=1 group by s.seat_id having count(*)>1) d');assert dup=='0'
    winner=next(r['id'] for r in responses if r['category']=='success')
    L.api(e,f'/api/reservations/{winner}/cancel',{'reasonText':'合成释放验证','reasonCode':'USER_CANCEL'},token,method='PUT')
    active=L.sql(e,f'select count(*) from reservation_occupancy where reservation_id={winner} and active_flag=1');assert active=='0'
    repeat=L.request(e['base'],f'/api/reservations/{winner}/cancel',{'reasonText':'合成释放验证','reasonCode':'USER_CANCEL'},token,method='PUT')[0]
    assert L.sql(e,f'select count(*) from reservation_occupancy where reservation_id={winner} and active_flag=1')=='0'
    restored=post(payload);assert restored['category']=='success',restored
    return {'spaceId':sid,'responses':responses,'fullConflict':rejected,'beforeRelease':counts,'adjacentOrOtherSeat':extra,'cancelReplayHttp':repeat,'afterReleaseRebook':restored,'final':db(sid)}
for mode in ['EXCLUSIVE','CAPACITY','ASSIGNED_SEAT']:check(mode,lambda mode=mode:race(mode))
def percentile(v,q):return sorted(v)[max(0,math.ceil(len(v)*q)-1)]
def measure(shape,concurrency,repeat,seconds,phase):
    fixtures=[L.fixture(e,u['admin']['token'],capacity=contract['capacityPerSpace']) for _ in range(1 if shape=='hotspot' else concurrency)]
    start=time.perf_counter();finish=start+seconds;samples=[];resources=[];stop=threading.Event()
    def worker(i):
        result=[]
        while time.perf_counter()<finish:result.append(post(fixtures[i%len(fixtures)]['payload']))
        return result
    def monitor():
        while not stop.is_set():
            sample={'elapsed':round(time.perf_counter()-start,3)}
            try:
                sample['jvmCpuPercentAndRssKiB']=L.command(['ps','-p',str(listeners['backend']['pid']),'-o','%cpu=,rss='])
                sample['containers']=[json.loads(line) for line in L.command(['docker','stats','--no-stream','--format','{{json .}}',*e['containers']]).splitlines()]
            except Exception as ex:sample['errorType']=type(ex).__name__
            resources.append(sample);stop.wait(2)
    monitorThread=threading.Thread(target=monitor);monitorThread.start()
    with concurrent.futures.ThreadPoolExecutor(concurrency) as pool:
        for group in pool.map(worker,range(concurrency)):samples+=group
    elapsed=time.perf_counter()-start;stop.set();monitorThread.join()
    name=f'{phase}-{shape}-{concurrency}-{repeat}';(out/(name+'.jsonl')).write_text(''.join(json.dumps(x)+'\n' for x in samples))
    counts={key:sum(x['category']==key for x in samples) for key in ['success','conflict','rateLimit','timeout','unexpected']}
    state=[{'venueId':f['venue']['id'],'spaceId':f['space']['id'],**db(f['space']['id'])} for f in fixtures]
    valid=all(s['outboxFacts']==s['outboxReservations']==s['reservations']==s['occupancies']==s['quantity']==s['commands']==sum(x['category']=='success' and x['spaceId']==s['spaceId'] for x in samples) and s['quantity']<=contract['capacityPerSpace'] for s in state)
    identityRows=command_count(samples)
    valid=valid and identityRows==counts['success']
    lat=[s['ms'] for s in samples]
    return {'scenario':name,'phase':phase,'shape':shape,'concurrency':concurrency,'repeat':repeat,'seconds':seconds,'elapsedIncludingDrain':elapsed,'samples':len(samples),'counts':counts,'reasons':dict(collections.Counter(s.get('reason') for s in samples if s['category']=='conflict')),'p50ms':percentile(lat,.5),'p95ms':percentile(lat,.95),'p99ms':percentile(lat,.99),'completedPerSecond':len(samples)/elapsed,'successPerSecond':counts['success']/elapsed,'identityRows':identityRows,'data':state,'resources':resources,'passed':bool(samples) and valid and counts['unexpected']==counts['timeout']==0,'raw':name+'.jsonl'}
if all(x['passed'] for x in rows):
    for shape in contract['shapes']:
        rows.append(measure(shape,2,0,contract['warmupSeconds'],'warmup'))
        if not a.smoke:
            for concurrency in contract['concurrency']:
                for repeat in range(1,contract['repeats']+1):
                    result=measure(shape,concurrency,repeat,contract['durationSeconds'],'formal');rows.append(result);print(json.dumps({k:result[k] for k in ['scenario','passed','counts','p95ms','successPerSecond']}),flush=True)
report={**L.provenance(e),'case':'C2','contract':contract,'contractSha256':hashlib.sha256((L.HERE/'load-contract.json').read_bytes()).hexdigest(),'smokeOnly':a.smoke,'results':rows,'passed':all(r['passed'] for r in rows),'limits':['closed-loop client and application share host; no production SLA','JVM ps %cpu is process lifetime average; Docker CPU is interval sample','no per-request DB lock wait or queue metric; no Redis outage claim','warmup excluded from formal distributions']}
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('C2 passed='+str(report['passed']));raise SystemExit(0 if report['passed'] else 1)
