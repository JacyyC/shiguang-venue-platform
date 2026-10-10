#!/usr/bin/env python3
"""Owned local resources and evidence. No default persistent DB; stdlib only.
Adapted concepts: final-remediation s02/run.py and s05/stored-integrations.py.
"""
import signal
import argparse, datetime, fcntl, hashlib, json, os, pathlib, secrets, socket, subprocess, time, urllib.request, urllib.error, uuid
HERE=pathlib.Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'backend/pom.xml').exists())
OWNER='SHIGUANG-RELIABILITY-SHOWCASE-20260919'
def command(args,**kwargs):
    return subprocess.run(args,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kwargs).stdout.strip()
def port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def request(base,path,body=None,token=None,headers=None,method=None):
    h={'Content-Type':'application/json',**(headers or {})}
    if token:h['Authorization']='Bearer '+token
    req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=h,method=method or ('POST' if body is not None else 'GET'))
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=60) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.load(e)
def api(env,path,body=None,token=None,method=None):
    status,r=request(env['base'],path,body,token,method=method)
    if status!=200 or r.get('code')!=0:raise AssertionError(f'{path}: HTTP {status}, code={r.get("code")}, message={r.get("message")}')
    return r.get('data')
def owned(env):
    for cid in env['containers']:
        d=json.loads(command(['docker','inspect',cid]))[0]
        assert d['Config']['Labels'].get('lab.owner')==OWNER and d['Config']['Labels'].get('lab.run')==env['run'], 'container ownership changed'
def sql(env,statement,db=None):
    owned(env)
    # Password through stdin to container shell environment, never command arguments/logs.
    p=subprocess.run(['docker','exec','-i',env['containers'][0],'sh','-c','read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -uroot -N -B --default-character-set=utf8mb4 '+(db or env['db'])],input=env['password']+'\n'+statement+'\n',text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if p.returncode:raise RuntimeError(p.stderr.replace(env['password'],'<secret>'))
    return p.stdout.strip()
def exclusive(e):
    handle=(ROOT/'.run'/e['run']/'experiment.lock').open('a')
    fcntl.flock(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
    return handle

def load(run):
    p=ROOT/'.run'/run/'env.json';e=json.loads(p.read_text());owned(e);return e

def start(run):
    assert run.startswith('sgrel-') and all(c.isalnum() or c=='-' for c in run)
    folder=ROOT/'.run'/run;folder.mkdir(parents=True,exist_ok=False)
    password=secrets.token_hex(24)
    e={'run':run,'password':password,'labSecret':secrets.token_hex(24),'jwt':secrets.token_hex(48),'db':'reliability','containers':[],'backendPort':port(),'frontendPort':port(),'configuration':json.loads((HERE/'load-contract.json').read_text())['application']}
    e['base']=f'http://127.0.0.1:{e["backendPort"]}';e['front']=f'http://127.0.0.1:{e["frontendPort"]}'
    for service,image,internal,extra in [('mysql','mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb',3306,['--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--log-bin-trust-function-creators=1']),('redis','redis@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99',6379,[])]:
        args=['docker','run','-d','--name',run+'-'+service,'--label','lab.owner='+OWNER,'--label','lab.run='+run,'-p',f'127.0.0.1::{internal}']
        ce=os.environ.copy()
        if service=='mysql':
            ce.update(MYSQL_ROOT_PASSWORD=password,MYSQL_DATABASE=e['db']);args+=['-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE','-e','TZ=Asia/Shanghai']
        cid=command(args+[image]+extra,env=ce);e['containers'].append(cid)
        e[service+'Port']=int(command(['docker','port',cid,str(internal)]).split(':')[-1])
    p=folder/'env.json';p.write_text(json.dumps(e));p.chmod(0o600)
    for _ in range(90):
        try:sql(e,'select 1');break
        except RuntimeError:time.sleep(1)
    else:raise RuntimeError('MySQL not ready')
    launch(e,folder)

def launch(e,folder):
    password=e['password']
    config=e['configuration']
    p=folder/'env.json'
    for n in ['backendPort','frontendPort']:
        with socket.socket() as sock:
            if sock.connect_ex(('127.0.0.1',e[n]))==0:raise RuntimeError('Selected port occupied; refusing reuse')
    env={**os.environ,'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(e['backendPort']),'MYSQL_HOST':'127.0.0.1','MYSQL_PORT':str(e['mysqlPort']),'MYSQL_DATABASE':e['db'],'MYSQL_USER':'root','MYSQL_PASSWORD':password,'REDIS_HOST':'127.0.0.1','REDIS_PORT':str(e['redisPort']),'JWT_SECRET':e['jwt'],'SPRING_PROFILES_ACTIVE':'reliability-lab','LAB_SECRET':e['labSecret'],'APP_NOTICE_SMTP_ENABLED':'false','APP_PAYMENT_ALIPAY_SANDBOX_ENABLED':'false','APP_STORED_VALUE_ALIPAY_SANDBOX_ENABLED':'false','APP_PROVIDER_OBSERVABILITY_SCHEDULED_ENABLED':'false','CORS_ALLOWED_ORIGINS':e['front'],'RATE_LIMIT_PER_MINUTE':str(config['rateLimitPerMinute']),'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE':str(config['hikariMaximumPoolSize']),'SERVER_TOMCAT_THREADS_MAX':str(config['tomcatMaxThreads']),'JAVA_TOOL_OPTIONS':'-Xmx'+config['jvmMaxHeap']}
    args=['mvn','-B','spring-boot:test-run','-Dspring-boot.run.useTestClasspath=true','-Dspring-boot.run.main-class=com.example.lab.ReliabilityLabApplication']
    p1=subprocess.Popen(args,cwd=ROOT/'backend',env=env,stdout=(folder/'backend.log').open('w'),stderr=subprocess.STDOUT,start_new_session=True)
    e['backendPid']=p1.pid
    env['VITE_API_PROXY_TARGET']=e['base']
    p2=subprocess.Popen(['npm','exec','vite','--','--host','127.0.0.1','--port',str(e['frontendPort']),'--strictPort'],cwd=ROOT/'frontend',env=env,stdout=(folder/'frontend.log').open('w'),stderr=subprocess.STDOUT,start_new_session=True)
    e['frontendPid']=p2.pid;p.write_text(json.dumps(e))
    for _ in range(150):
        if p1.poll() is not None:raise RuntimeError('Backend exited; inspect owned backend.log')
        try:
            if request(e['base'],'/actuator/health')[0]==200:break
        except Exception:pass
        time.sleep(1)
    else:raise RuntimeError('Backend not ready')
    if p2.poll() is not None:raise RuntimeError('Frontend exited; inspect owned frontend.log')
    print(json.dumps({'run':e['run'],'backend':e['base'],'frontend':e['front'],'containers':e['containers']}))

def listeners(e):
    """Validate current listeners, not historical PIDs. Secret proves backend identity."""
    assert control(e,'barrier').get('arrived') in [True,False]
    result={}
    for kind,marker in [('backend','com.example.lab.ReliabilityLabApplication'),('frontend','vite')]:
        pid=int(command(['lsof','-nP','-t','-iTCP:'+str(e[kind+'Port']),'-sTCP:LISTEN']))
        cwd=command(['lsof','-a','-p',str(pid),'-d','cwd','-Fn'])
        assert 'n'+str(ROOT/kind) in cwd.splitlines(), 'listener cwd mismatch'
        cmd=command(['ps','-p',str(pid),'-o','command='])
        assert marker in cmd, 'listener command mismatch'
        result[kind]={'pid':pid,'pgid':int(command(['ps','-p',str(pid),'-o','pgid=']))}
    return result

def stop(e):
    owned(e)
    processes=listeners(e)
    for info in processes.values():
        assert info['pgid'] != os.getpgrp()
        os.killpg(info['pgid'],signal.SIGTERM)
    command(['docker','stop',*e['containers']])
    print(json.dumps({'run':e['run'],'stopped':True,'processes':processes}))

def seed(e):
    users={}
    for name in ['owner','other','admin']:
        username='lab_'+name
        request(e['base'],'/api/auth/register',{'username':username,'password':e['password'],'nickname':'合成用户'})
        if name=='admin':sql(e,"INSERT IGNORE INTO sys_user_role(user_id,role_id) SELECT id,3 FROM sys_user WHERE username='lab_admin';")
        users[name]=api(e,'/api/auth/login',{'username':username,'password':e['password']})
    return users

def fixture(e,admin,mode='CAPACITY',capacity=10):
    name='lab-'+uuid.uuid4().hex[:12]
    v=api(e,'/api/admin/venues',{'name':name,'address':'合成实验场所','area':'实验区','openTime':'08:00:00','closeTime':'22:00:00','status':'ACTIVE'},admin)
    s=api(e,'/api/admin/spaces',{'venueId':v['id'],'name':name,'type':'MEETING','code':name,'capacity':capacity,'pricePerHour':20,'status':'ACTIVE'},admin)
    rule={'resourceCategory':'STUDY_ROOM','bookingMode':mode,'capacityLimit':capacity if mode=='CAPACITY' else None,'seats':[{'seatCode':'A1','seatLabel':'A1'},{'seatCode':'A2','seatLabel':'A2'}] if mode=='ASSIGNED_SEAT' else [],'reasonText':'合成实验'}
    api(e,f'/api/admin/spaces/{s["id"]}/resource-rule',rule,admin,method='PUT')
    day=(datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date()+datetime.timedelta(days=1)).isoformat()
    payload={'spaceId':s['id'],'startTime':day+'T10:00:00','endTime':day+'T12:00:00','participantCount':1,'seatIds':[],'contactName':'实验用户','contactPhone':'13900000000'}
    return {'venue':v,'space':s,'payload':payload}

def control(e,action,body=None):return request(e['base'],'/__lab/'+action,body,headers={'X-Lab-Secret':e['labSecret']})[1]
def output(run,case):
    out=HERE.parent/'runs'/run/case;out.mkdir(parents=True,exist_ok=False);return out

def provenance(e):
    paths=command(['git','ls-files','backend','frontend']).splitlines()
    paths += ['backend/src/test/java/com/example/lab/ReliabilityLabApplication.java']
    manifest={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sorted(set(paths)) if (ROOT/p).is_file()}
    diff=command(['git','diff','HEAD','--','backend','frontend'])
    return {'run':e['run'],'head':command(['git','rev-parse','HEAD']),'productDiffSha256':hashlib.sha256(diff.encode()).hexdigest(),'sourceManifestSha256':hashlib.sha256(json.dumps(manifest,sort_keys=True).encode()).hexdigest(),'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'containers':[{k:d[k] for k in ['Id','Image']} for cid in e['containers'] for d in json.loads(command(['docker','inspect',cid]))],'limits':['local isolated synthetic data','test classpath transaction injection','no production or real channels']}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['start','seed','stop']);p.add_argument('--run',required=True);a=p.parse_args()
    if a.action=='start':start(a.run)
    elif a.action=='stop':stop(load(a.run))
    else:
        e=load(a.run);u=seed(e);f=fixture(e,u['admin']['token']);folder=ROOT/'.run'/a.run
        p=folder/'fixture.json';p.write_text(json.dumps({'users':u,'fixture':f}));p.chmod(0o600);print('Synthetic fixture ready')
