import lab as L
import argparse,json,uuid,time,concurrent.futures
p=argparse.ArgumentParser();p.add_argument('--run',required=True);a=p.parse_args();e=L.load(a.run);lock=L.exclusive(e);out=L.output(a.run,'c1-controls');u=json.loads((L.ROOT/'.run'/a.run/'fixture.json').read_text())['users'];token=u['owner']['token'];rows=[]
def key():return 'control-'+uuid.uuid4().hex
def get(k,t=token):return L.api(e,'/api/reservations/submission-result?idempotencyKey='+k,token=t)
def post(f,k,t=token):return L.request(e['base'],'/api/reservations',f,t,{'Idempotency-Key':k})
def check(name,fn):
    try:actual=fn();rows.append({'scenario':name,'passed':True,'actual':actual})
    except Exception as ex:rows.append({'scenario':name,'passed':False,'error':str(ex)})
def absent():
    f=L.fixture(e,u['admin']['token']);k=key();assert get(k)['status']=='NONE';assert L.sql(e,"select count(*) from reservation_create_command where idempotency_key='"+k+"'")=='0';s,r=post(f['payload'],k);assert s==200 and r['code']==0;rid=r['data']['id'];assert get(k)['reservationId']==rid;return {'notForwardedRows':0,'retryId':rid}
def rolledback():
    f=L.fixture(e,u['admin']['token']);k=key();L.control(e,'arm',{'key':k,'rollback':True})
    with concurrent.futures.ThreadPoolExecutor() as pool:
        fut=pool.submit(post,f['payload'],k)
        for _ in range(100):
            observation=L.control(e,'barrier')
            if observation['arrived']:break
            time.sleep(.05)
        assert observation.get('transactionActive') and observation.get('ownConnectionRows')==1
        assert get(k)['status']=='NONE';L.control(e,'release',{});status,_=fut.result(15);assert status==500
    sid=f['space']['id'];assert L.sql(e,f'select count(*) from reservation where space_id={sid}')=='0';assert get(k)['status']=='NONE'
    status,r=post(f['payload'],k);assert status==200 and r['code']==0;return {'injection':observation,'rollbackHttp':500,'retryId':r['data']['id']}
def replay_and_isolation():
    f=L.fixture(e,u['admin']['token']);k=key();status,r=post(f['payload'],k);assert status==200;rid=r['data']['id']
    assert get(k)['reservationId']==rid
    assert post(f['payload'],k)[1]['data']['id']==rid
    status,mismatch=post({**f['payload'],'participantCount':2},k);assert status==409 and mismatch['data']['reasonCode']=='SUBMISSION_KEY_REUSED'
    assert get(k,u['other']['token'])['status']=='NONE'
    status,other=post(f['payload'],k,u['other']['token']);assert status==200 and other['data']['id']!=rid
    assert get(k)['reservationId']==rid
    return {'id':rid,'otherId':other['data']['id'],'mismatchCode':mismatch['data']['reasonCode'],'sameKeyReplay':True}
def conflict():
    f=L.fixture(e,u['admin']['token'],mode='EXCLUSIVE');assert post(f['payload'],key())[0]==200
    status,r=post(f['payload'],key());assert status==409;assert r['data']['reasonCode']=='EXCLUSIVE_OVERLAP';return {'http':status,'reason':r['data']['reasonCode']}
check('never-arrived-same-key',absent);check('real-rollback-same-key',rolledback);check('committed-replay-mismatch-user-isolation',replay_and_isolation);check('real-inventory-conflict',conflict)
report={**L.provenance(e),'case':'C1-controls','results':rows,'passed':all(r['passed'] for r in rows)};(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(0 if report['passed'] else 1)
