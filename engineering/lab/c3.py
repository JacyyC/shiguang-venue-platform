"""Four explicit MySQL suites plus a real application DEV settlement/refund trace."""
import argparse,datetime,json,os,subprocess,time,uuid,xml.etree.ElementTree as ET
from decimal import Decimal
import lab as L
p=argparse.ArgumentParser();p.add_argument('--run',required=True);a=p.parse_args();e=L.load(a.run);lock=L.exclusive(e);L.listeners(e);out=L.output(a.run,'c3');results=[]
u=json.loads((L.ROOT/'.run'/a.run/'fixture.json').read_text())['users']
def readrows(query,db=None):return [json.loads(line) for line in L.sql(e,query,db).splitlines() if line]
def clean(s):return '\n'.join(x.rstrip() for x in s.replace(e['password'],'<secret>').replace(str(L.ROOT),'<repo>').replace(str(L.pathlib.Path.home()),'<user-home>').splitlines())+'\n'
cases=[('StoredValueMySqlIntegrationTest','stored.value'),('StoredValueOrderPaymentMySqlIntegrationTest','stored.value.order'),('StoredValueApprovalMySqlIntegrationTest','stored.value.approval'),('StoredValueReconciliationMySqlIntegrationTest','stored.value.reconciliation')]
for i,(cls,prefix) in enumerate(cases):
    database='sgrel_c3_'+str(i)+'_'+uuid.uuid4().hex[:10];L.sql(e,f'CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
    assert L.sql(e,f"select count(*) from information_schema.tables where table_schema='{database}'")=='0'
    properties=L.ROOT/'.run'/a.run/(cls+'.properties')
    properties.write_text(f'{prefix}.jdbc-url=jdbc:mysql://127.0.0.1:{e["mysqlPort"]}/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai\n{prefix}.jdbc-user=root\n{prefix}.jdbc-password={e["password"]}\n');properties.chmod(0o600)
    args=['mvn','-B',f'-Dtest={cls}',f'-D{prefix}.integration=true','-Dsurefire.systemPropertiesFile='+str(properties),'test']
    environment=os.environ.copy()
    started=time.time();r=subprocess.run(args,cwd=L.ROOT/'backend',env=environment,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (out/(cls+'.txt')).write_text(clean(r.stdout))
    xml=L.ROOT/'backend/target/surefire-reports'/('TEST-com.example.venue.storedvalue.'+cls+'.xml')
    try:
        assert xml.stat().st_mtime>=started,'stale XML';root=ET.parse(xml).getroot();counts={k:int(root.attrib[k]) for k in ['tests','failures','errors','skipped']};names=[t.attrib['name'] for t in root.findall('testcase')]
        assert r.returncode==0 and counts['tests']==len(names)>0 and not any(counts[k] for k in ['failures','errors','skipped'])
        migrations=L.sql(e,'select version,checksum,success from flyway_schema_history where version is not null order by installed_rank',database)
        assert migrations==L.sql(e,'select version,checksum,success from flyway_schema_history where version is not null order by installed_rank')
        facts={
          'payments':readrows("select JSON_OBJECT('paymentId',id,'orderId',order_id,'accountId',account_id,'status',status,'amount',amount,'principal',principal_amount,'bonus',bonus_amount) from stored_value_order_payment order by id",database),
          'refunds':readrows("select JSON_OBJECT('refundId',id,'orderId',order_id,'paymentId',order_payment_id,'status',status,'amount',amount,'principal',principal_amount,'bonus',bonus_amount,'compensation',compensation_amount) from stored_value_order_refund order by id",database),
          'differences':readrows("select JSON_OBJECT('id',id,'runId',run_id,'type',difference_type,'status',status) from stored_value_reconciliation_difference order by id",database)}
        (out/(cls+'-facts.json')).write_text(json.dumps(facts,ensure_ascii=False,indent=2)+'\n')
        results.append({'scenario':cls,'passed':True,'exit':r.returncode,'database':database,'initialTables':0,**counts,'testNames':names,'migrationsMatch':True,'facts':cls+'-facts.json'})
    except Exception as ex:results.append({'scenario':cls,'passed':False,'exit':r.returncode,'error':str(ex) or type(ex).__name__})
    print(json.dumps(results[-1],ensure_ascii=False),flush=True)
def live():
    admin=u['admin']['token'];token=u['owner']['token'];uid=int(L.sql(e,"select id from sys_user where username='lab_owner'"));stamp=uuid.uuid4().hex[:12]
    product=L.api(e,'/api/admin/stored-value/products',{'productCode':'LAB_'+stamp,'productName':'合成账务实验'},admin);pid=product['id'];assert product['environment']=='DEV'
    L.api(e,f'/api/admin/stored-value/products/{pid}/flags',{'spendEnabled':True},admin,method='PUT')
    L.api(e,f'/api/admin/stored-value/users/{uid}/identity-verifications',{'environment':'DEV','evidenceDigest':'0'*64},admin)
    recharge=L.api(e,'/api/stored-value/me/recharges',{'productId':pid,'principalAmount':100,'currency':'CNY','clientRequestId':'c3-'+stamp,'providerKey':'stored-value.dev'},token)
    charged=L.api(e,f'/api/stored-value/me/recharges/{recharge["rechargeNo"]}/dev-callback',{'status':'SUCCESS'},token);assert charged['status']=='SUCCEEDED',charged['status']
    aid=int(L.sql(e,f'select id from stored_value_account where user_id={uid} and product_id={pid}'))
    def snapshot():
        account=readrows(f"select JSON_OBJECT('principal',principal_available,'bonus',(select coalesce(sum(available_amount),0) from stored_value_bonus_batch where account_id={aid}),'principalFrozen',principal_frozen,'bonusFrozen',(select coalesce(sum(frozen_amount),0) from stored_value_bonus_batch where account_id={aid})) from stored_value_account where id={aid}")[0]
        totals=readrows(f"select JSON_OBJECT('bucket',bucket,'net',sum(case when direction='CREDIT' then amount else -amount end)) from stored_value_ledger_entry where account_id={aid} group by bucket order by bucket")
        balanced=L.sql(e,f"select count(*) from (select t.id from stored_value_ledger_transaction t join stored_value_ledger_entry x on x.transaction_id=t.id where t.account_id={aid} group by t.id having sum(case when direction='CREDIT' then x.amount else -x.amount end)<>0) q");assert balanced=='0'
        ledger={x['bucket']:Decimal(str(x['net'])) for x in totals}
        for field,bucket in [('principal','PRINCIPAL_AVAILABLE'),('bonus','BONUS_AVAILABLE'),('principalFrozen','PRINCIPAL_FROZEN'),('bonusFrozen','BONUS_FROZEN')]:assert Decimal(str(account[field]))==ledger.get(bucket,Decimal(0))
        return {'account':account,'ledgerProjection':totals,'unbalancedTransactions':int(balanced)}
    before=snapshot();f=L.fixture(e,admin);reservation=L.api(e,'/api/reservations',f['payload'],token);rid=reservation['id'];L.api(e,f'/api/admin/reservations/{rid}/approve',{'reason':'合成审核'},admin,method='PUT');oid=int(L.sql(e,f'select id from biz_order where reservation_id={rid}'))
    pay={'productId':pid,'idempotencyKey':'c3-pay-'+stamp};first=L.api(e,f'/api/stored-value/me/orders/{oid}/payments',pay,token);replay=L.api(e,f'/api/stored-value/me/orders/{oid}/payments',pay,token);assert replay['replayed'] and replay['paymentId']==first['paymentId']
    after=snapshot();assert Decimal(str(before['account']['principal']))-Decimal(str(after['account']['principal']))==Decimal(str(first['principalAmount']));assert Decimal(str(before['account']['bonus']))-Decimal(str(after['account']['bonus']))==Decimal(str(first['bonusAmount']))
    paid=L.sql(e,f"select concat(status,':',payment_tool_type,':',payment_fact_id) from biz_order where id={oid}");assert paid=='PAID:STORED_VALUE:'+first['paymentNo'];assert L.sql(e,f"select status from reservation where id={rid}")=='PAID'
    assert L.sql(e,f'select count(*) from payment_record where order_id={oid}')=='0' # Stored value has its own payment fact.
    refund=L.api(e,f'/api/orders/{oid}/refunds',{'reason':'合成原桶退款'},token);fid=refund['id'];L.api(e,f'/api/admin/refunds/{fid}/approve',{'reason':'合成审核'},admin,method='PUT');L.api(e,f'/api/admin/refunds/{fid}/success',{},admin,method='PUT');end=snapshot();assert before['account']==end['account']
    replayStatus=L.request(e['base'],f'/api/admin/refunds/{fid}/success',{},admin,method='PUT')[0];assert replayStatus==400
    counts=L.sql(e,f"select (select count(*) from stored_value_order_payment where order_id={oid}), (select count(*) from stored_value_order_refund where order_id={oid}), (select count(*) from reservation_occupancy where reservation_id={rid} and active_flag=1)");assert counts=='1\t1\t0'
    assert L.sql(e,f'select status from biz_order where id={oid}')=='REFUNDED'
    allocations=readrows(f"select JSON_OBJECT('paymentAllocationId',p.id,'bucket',p.bucket,'paymentAmount',p.amount,'refundedAmount',p.refunded_amount,'sourceBatchId',p.bonus_batch_id,'restoredBatchId',r.restored_bonus_batch_id,'refundAmount',r.amount) from stored_value_order_payment_allocation p join stored_value_order_refund_allocation r on r.payment_allocation_id=p.id where p.payment_id={first['paymentId']}")
    for x in allocations:assert x['paymentAmount']==x['refundAmount']==x['refundedAmount'] and x['sourceBatchId']==x['restoredBatchId']
    assert len(allocations)>0
    day=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).date().isoformat();recon=L.api(e,'/api/admin/stored-value/reconciliation/run',{'reconcileDate':day},admin);assert recon['status']=='COMPLETED' and recon['differenceCount']==0,recon
    return {'productId':pid,'accountId':aid,'reservationId':rid,'orderId':oid,'rechargeId':recharge['id'],'payment':first,'samePaymentReplay':replay['replayed'],'orderPaidFact':paid,'before':before,'afterPayment':after,'afterRefund':end,'refundRecordId':fid,'repeatRefundHttp':replayStatus,'allocations':allocations,'reconciliation':recon,'externalPaymentRecords':0,'finalOrderStatus':'REFUNDED','activeOccupancy':0}
try:actual=live();results.append({'scenario':'real-application-dev-settlement-refund','passed':True,'actual':actual})
except Exception as ex:results.append({'scenario':'real-application-dev-settlement-refund','passed':False,'error':str(ex) or type(ex).__name__})
report={**L.provenance(e),'case':'C3','results':results,'passed':all(r['passed'] for r in results),'limits':['synthetic DEV funds only; external providers disabled','four suites use isolated schemas; order suite mocks OrderLocalSettlementService, including rollback injection','real HTTP case uses actual settlement, ledger, order transitions and refund services','counterexample SQL and compensation policy exercised inside explicit suites; tests are not production claims']};(out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2));raise SystemExit(0 if report['passed'] else 1)
