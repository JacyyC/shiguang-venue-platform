import copy,json,tempfile,pathlib,unittest
from run import validate
class Reports(unittest.TestCase):
    def test_missing_failed_duplicate_and_wrong_run_are_rejected(self):
        good={'run':'test','head':'abc','case':'C1','passed':True,'results':[{'engine':k,'passed':True} for k in ['chromium','firefox','webkit']]}
        with tempfile.TemporaryDirectory() as folder:
            p=pathlib.Path(folder)/'report.json'
            with self.assertRaises(FileNotFoundError):validate(p,'test','C1')
            p.write_text(json.dumps(good));self.assertEqual(validate(p,'test','C1'),3)
            for kind in ['empty','failed','duplicate','wrong-run','missing-engine']:
                d=copy.deepcopy(good)
                if kind=='empty':d['results']=[]
                if kind=='failed':d['results'][0]['passed']=False
                if kind=='duplicate':d['results'].append(d['results'][0])
                if kind=='wrong-run':d['run']='stale'
                if kind=='missing-engine':d['results'].pop()
                p.write_text(json.dumps(d))
                with self.subTest(kind=kind),self.assertRaises(ValueError):validate(p,'test','C1')
    def test_partial_c2_load_is_not_accepted(self):
        d={'run':'test','head':'abc','case':'C2','passed':True,'smokeOnly':False,'results':[{'scenario':s,'passed':True} for s in ['EXCLUSIVE','CAPACITY','ASSIGNED_SEAT']]}
        with tempfile.TemporaryDirectory() as folder:
            p=pathlib.Path(folder)/'report.json';p.write_text(json.dumps(d))
            with self.assertRaises(ValueError):validate(p,'test','C2')
if __name__=='__main__':unittest.main()
