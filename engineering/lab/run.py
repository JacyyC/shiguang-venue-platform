"""Reproducible case entry, fresh outputs and fail-closed report validation."""
import argparse,json,pathlib,subprocess,sys
import lab as L

def validate(path,run,case):
    d=json.loads(path.read_text())
    if d.get('run')!=run or d.get('case')!=case or not d.get('head') or d.get('passed') is not True:raise ValueError('invalid identity/version or failed report')
    rows=d.get('results',[])
    if not rows or any(r.get('passed') is not True for r in rows):raise ValueError('missing or failed assertions')
    keys=[r.get('engine',r.get('scenario')) for r in rows]
    if None in keys or len(keys)!=len(set(keys)):raise ValueError('missing or duplicate scenario')
    if case=='C1' and set(keys)!={'chromium','firefox','webkit'}:raise ValueError('missing required browser')
    if case=='C1-controls' and set(keys)!={'never-arrived-same-key','real-rollback-same-key','committed-replay-mismatch-user-isolation','real-inventory-conflict'}:raise ValueError('missing required control')
    if case=='C2':
        required={'EXCLUSIVE','CAPACITY','ASSIGNED_SEAT'}|{f'warmup-{shape}-2-0' for shape in ['hotspot','distributed']}|{f'formal-{shape}-{n}-{r}' for shape in ['hotspot','distributed'] for n in [1,8] for r in [1,2]}
        if d.get('smokeOnly') is not False or set(keys)!=required:raise ValueError('missing formal load or correctness scenario')
    if case=='C3' and set(keys)!={'StoredValueMySqlIntegrationTest','StoredValueOrderPaymentMySqlIntegrationTest','StoredValueApprovalMySqlIntegrationTest','StoredValueReconciliationMySqlIntegrationTest','real-application-dev-settlement-refund'}:raise ValueError('missing stored value suite or real settlement')
    if case=='C3':
        for row in rows:
            if row['scenario'].endswith('MySqlIntegrationTest') and (row.get('exit')!=0 or row.get('tests',0)<=0 or any(row.get(k,1)!=0 for k in ['failures','errors','skipped'])):raise ValueError('failed, empty or skipped stored value suite')
    return len(rows)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('case',choices=['c1','c2','c3']);p.add_argument('--run',required=True);a=p.parse_args()
    jobs={'c1':[('c1.py','c1','C1'),('c1-controls.py','c1-controls','C1-controls')],'c2':[('c2.py','c2','C2')],'c3':[('c3.py','c3','C3')]}[a.case]
    out=L.HERE.parent/'runs'/a.run;out.mkdir(parents=True,exist_ok=True)
    result=[]
    for script,folder,case in jobs:
        log=out/(script+'.stdout.txt')
        with log.open('x') as stream:r=subprocess.run([sys.executable,str(L.HERE/script),'--run',a.run],stdout=stream,stderr=subprocess.STDOUT)
        errors=[];count=0
        try:count=validate(out/folder/'report.json',a.run,case)
        except Exception as ex:errors.append(str(ex))
        if r.returncode:errors.append('child exit '+str(r.returncode))
        result.append({'case':case,'exit':r.returncode,'assertions':count,'errors':errors})
    (out/(a.case+'-summary.json')).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));sys.exit(int(any(x['errors'] for x in result)))
