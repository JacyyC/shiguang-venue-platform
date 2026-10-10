import lab as L
import argparse,json,subprocess
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--case',default='c1');p.add_argument('--engines',default='chromium,firefox,webkit');a=p.parse_args()
e=L.load(a.run);lock=L.exclusive(e);out=L.output(a.run,a.case);auth=json.loads((L.ROOT/'.run'/a.run/'fixture.json').read_text())['users'];rows=[]
for engine in a.engines.split(','):
    f=L.fixture(e,auth['admin']['token']);result=out/(engine+'.json')
    proc=subprocess.run(['node',str(L.HERE/'c1-browser.mjs')],input=json.dumps({'env':e,'users':auth,'fixture':f,'engine':engine,'output':str(result)}),text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/(engine+'.txt')).write_text(proc.stdout.replace(str(L.ROOT),'<repo>'))
    d=json.loads(result.read_text()) if result.exists() else {'error':'missing browser output'}
    sid=f['space']['id'];tables={'reservations':f'select count(*) from reservation where space_id={sid}', 'commands':f'select count(*) from reservation_create_command where reservation_id in (select id from reservation where space_id={sid})', 'occupancies':f'select count(*) from reservation_occupancy where space_id={sid} and active_flag=1', 'businessEvents':f"select count(*) from business_event where event_type='RESERVATION_CREATED' and entity_id in (select id from reservation where space_id={sid})", 'funnelEvents':f"select count(*) from funnel_event where event_name='RESERVATION_SUBMITTED' and reservation_id in (select id from reservation where space_id={sid})", 'ownerNotices':f"select count(*) from notice_message where title='预约已提交' and business_id in (select id from reservation where space_id={sid})", 'statusLogs':f'select count(*) from reservation_status_log where reservation_id in (select id from reservation where space_id={sid})'}
    counts={k:int(L.sql(e,v)) for k,v in tables.items()}
    rows.append({'engine':engine,'exit':proc.returncode,'browser':d,'counts':counts,'passed':proc.returncode==0 and all(v==1 for v in counts.values())})
report={**L.provenance(e),'case':'C1','results':rows,'passed':all(x['passed'] for x in rows)}
(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(0 if report['passed'] else 1)
