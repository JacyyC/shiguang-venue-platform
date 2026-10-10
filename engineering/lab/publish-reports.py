"""Copy the reviewed historical reports; this never runs an experiment."""
import hashlib,json,pathlib
HERE=pathlib.Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'backend/pom.xml').exists())
out=ROOT/'frontend/public/engineering';out.mkdir(exist_ok=True)
manifest=[]
for case in ['C1','C2','C3']:
 p=ROOT/'engineering/reports'/(case+'.json');raw=p.read_bytes();d=json.loads(raw)
 assert d['case']==case
 (out/(case+'.json')).write_bytes(raw)
 manifest.append({'case':case,'url':'/engineering/'+case+'.json','run':d['run'],'head':d['head'],'sha256':hashlib.sha256(raw).hexdigest(),'source':str(p.relative_to(ROOT))})
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('Published 3 historical reports; byte identity and source hashes retained')
