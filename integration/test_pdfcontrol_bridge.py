"""Real stdio MCP, real legacy engine and synthetic PDFs; no model, production data or network service."""
import argparse,hashlib,json,os,shutil,subprocess,sys,time,unittest
from pathlib import Path
import pymupdf

parser=argparse.ArgumentParser();parser.add_argument('--package',required=True);parser.add_argument('--output',required=True);ARGS=parser.parse_args()
PACKAGE=Path(ARGS.package).resolve();OUT=Path(ARGS.output).resolve();ROOT=OUT/'PROJECTS';PROJECT=ROOT/'123456'
OUT.mkdir(parents=True,exist_ok=True);PROJECT.mkdir(parents=True,exist_ok=True)

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def pdf(path,texts):
    path.parent.mkdir(parents=True,exist_ok=True)
    with pymupdf.open() as doc:
        for text in texts:
            page=doc.new_page(width=800,height=600);page.insert_text((45,70),text,fontsize=10)
        doc.save(path)

class Client:
    def __init__(self,package=PACKAGE):
        env=dict(os.environ,PDFCONTROL_PACKAGE=str(package),PDFCONTROL_PROJECTS_ROOT=str(ROOT),PDFCONTROL_PRODUCTIE_ROOT=str(ROOT))
        self.log=(OUT/('bridge.stderr.log' if package==PACKAGE else 'factory-bridge.stderr.log')).open('w',encoding='utf-8')
        self.process=subprocess.Popen([sys.executable,'-I','-B',str(package/'octop_mcp.py')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,encoding='utf-8',env=env)
        self.count=0;self.request('initialize',{'protocolVersion':'2025-06-18','capabilities':{},'clientInfo':{'name':'integration-test','version':'1'}})
    def request(self,method,params):
        self.count+=1;self.process.stdin.write(json.dumps({'jsonrpc':'2.0','id':self.count,'method':method,'params':params})+'\n');self.process.stdin.flush()
        line=self.process.stdout.readline()
        if not line:raise AssertionError('MCP stopped unexpectedly. See bridge.stderr.log.')
        value=json.loads(line)
        if 'error'in value:raise AssertionError(value['error'])
        return value['result']
    def call(self,name,args=None,error=False):
        result=self.request('tools/call',{'name':name,'arguments':args or {}})
        if bool(result.get('isError'))!=error:raise AssertionError(result)
        text=result['content'][0]['text'];return text if error else json.loads(text)
    def start(self,name,**args):return self.call(name,dict(project='123456',**args))
    def wait(self,record):
        deadline=time.monotonic()+180
        while record['status']in {'pending','running'}:
            if time.monotonic()>deadline:raise AssertionError('Job timeout: '+record['job_id'])
            record=self.call('pdfcontrol_job_status',{'project':'123456','job_id':record['job_id'],'wait_seconds':2})
        if record['status']=='failed':
            log=Path(record['run_folder'])/'worker.log';raise AssertionError(str(record)+'\n'+log.read_text(encoding='utf-8',errors='replace')[-5000:])
        return record
    def close(self):
        self.process.stdin.close();self.process.wait(timeout=10);self.log.close()

class Workflows(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client=Client()
        v='Projectnummer:\n123456\nVormtekening\nWA224_V\nWA133_W\nWapeningstekening\n'
        w='Projectnummer:\n123456\nWapeningstekening\nWA133_W\n'
        wrong='Projectnummer:\n123456\nWapeningstekening\nWA130_W\n'
        pdf(PROJECT/'loose/123456_WA224_V.pdf',[v]);pdf(PROJECT/'loose/123456_WA133_W.pdf',[w]);pdf(PROJECT/'wrong/123456_WA224_W.pdf',[wrong])
        pdf(PROJECT/'bundle.pdf',[v,w,'Projectnummer:\n123456\nWapeningstekening\nWA224_W\n'])
        for side,value,rev in [('old','2000','A'),('new','2100','B')]:
            path=PROJECT/side/'123456_A1_V.pdf'
            pdf(path,[f'Projectnummer: 123456\nVormtekening\nA1_V\nRevisie: {rev}\nDefinitief\nMaat: {value}'])
        pdf(PROJECT/'abwn-book.pdf',['Tekla Structures\nVB-BA1\nRenvooi\nProjectnummer ABWN // werknummer MBS:\n98765 / 123456','Tekla Structures\nH20\nSU2\nINSTORTVOORZIENINGEN\nVORM BALKON\nBA1','Tekla Structures\nSU2\nH20\nINSTORTVOORZIENINGEN\nVORM BALKON\nBA2'])
        cls.before={str(p):digest(p) for p in PROJECT.rglob('*.pdf')}
    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        for name,value in cls.before.items():
            if digest(Path(name))!=value:raise AssertionError('Original changed: '+name)
    def artifacts(self,record):
        self.assertTrue(record['source_unchanged']);self.assertFalse(record['transfer_approved'])
        self.assertTrue(record['artifacts'])
        for row in record['artifacts']:
            path=Path(row['path']);self.assertTrue(path.is_relative_to(PROJECT/'Controle'))
            with pymupdf.open(path) as doc:self.assertGreater(len(doc),0)
    def test_01_discovery_and_confinement(self):
        tools=self.client.request('tools/list',{})['tools'];names={t['name'] for t in tools}
        self.assertTrue({'pdfcontrol_start_extract','pdfcontrol_start_pairs','pdfcontrol_start_compare','pdfcontrol_regenerate_report','pdfcontrol_start_workflow','pdfcontrol_cancel_job'}<=names)
        for t in tools:
            if t['name'].startswith('pdfcontrol_start_'):self.assertFalse(t['annotations']['readOnlyHint'])
        for files in [['../outside.pdf'],[str(PROJECT/'bundle.pdf')],['bundle.pdf','bundle.pdf'],['Controle/input.pdf']]:
            self.client.call('pdfcontrol_start_pairs',{'project':'123456','files':files},error=True)
        self.client.call('pdfcontrol_job_status',{'project':'123456','job_id':'../../escape','wait_seconds':0},error=True)
    def test_02_confirmed_pairs(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_pairs',files=['loose/123456_WA224_V.pdf','loose/123456_WA133_W.pdf']))
        self.assertEqual(record['status'],'done');self.artifacts(record)
        generated=Path(record['run_folder'])/'engine/PDF_NOU/123456_WA224_W.pdf'
        self.assertEqual(digest(generated),digest(PROJECT/'loose/123456_WA133_W.pdf'))
        with pymupdf.open(generated) as doc:self.assertIn('WA133_W',doc[0].get_text())
        self.assertTrue(Path(record['provenance']).is_file())
    def test_03_wrong_existing_pair_is_partial(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_pairs',files=['loose/123456_WA224_V.pdf','loose/123456_WA133_W.pdf','wrong/123456_WA224_W.pdf']))
        self.assertEqual(record['status'],'partial');self.artifacts(record)
        self.assertEqual(digest(Path(record['run_folder'])/'engine/PDF_NOU/123456_WA224_W.pdf'),digest(PROJECT/'wrong/123456_WA224_W.pdf'))
        self.assertGreater(record['pair_summary']['unresolved'],0)
    def test_04_extract_uses_referenced_w(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_extract',files=['bundle.pdf']))
        self.artifacts(record);self.assertGreater(record['pair_summary']['created'],0)
        folder=Path(record['run_folder'])/'engine/PDF_NOU';self.assertTrue((folder/'123456_WA224_V.pdf').is_file())
        with pymupdf.open(folder/'123456_WA224_W.pdf') as doc:self.assertIn('WA133_W',doc[0].get_text())
        self.assertFalse(list(folder.glob('*_bron_W.pdf')))
    def test_05_compare_real_reports_and_vector_markers(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_compare',old_files=['old/123456_A1_V.pdf'],new_files=['new/123456_A1_V.pdf']))
        self.assertEqual(record['status'],'done');self.artifacts(record)
        data=json.loads(Path(record['results']).read_text(encoding='utf-8'))
        self.assertEqual(data['status'],'complete');self.assertTrue(data['bladen'])
        self.assertIn('viewer_markers',data['bladen'][0]);self.assertTrue(Path(record['differences_report']).is_file())
        with pymupdf.open(record['report']) as doc:
            text='\n'.join(p.get_text() for p in doc);self.assertIn('2000',text);self.assertIn('2100',text)
            colors=[g['color'] for p in doc for g in p.get_drawings() if g.get('color')]
            self.assertTrue(any(abs(c[0]-239/255)<.01 and abs(c[1]-68/255)<.01 for c in colors))
            doc[0].get_pixmap(matrix=pymupdf.Matrix(1,1),alpha=False).save(OUT/'compare-preview.png')
    def test_06_full_workflow_and_report_regeneration(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_workflow',files=['bundle.pdf']))
        self.artifacts(record);self.assertIn(record['status'],{'done','partial'})
        self.assertTrue(Path(record['results']).is_file());self.assertEqual(record['report_info']['pad'],record['report']);self.assertEqual(record['report_info']['scope'],'all')
        results=json.loads(Path(record['results']).read_text(encoding='utf-8'))
        self.assertGreater(len(results.get('tekeningen',[])),0)
        self.assertTrue(any('_checked' in row['path'] for row in record['artifacts']))
        with pymupdf.open(record['report']) as doc:
            self.assertGreater(len(doc),1);doc[0].get_pixmap(matrix=pymupdf.Matrix(1,1),alpha=False).save(OUT/'report-preview.png')
        regen=self.client.wait(self.client.start('pdfcontrol_regenerate_report',job_id=record['job_id'],include_drawings=True))
        self.artifacts(regen);self.assertTrue(Path(regen['report']).is_file());self.assertEqual(regen['report_info']['scope'],'all');self.assertEqual(regen['status'],record['status']);self.assertEqual(regen.get('limitation_codes'),record.get('limitation_codes'))
    def test_07_cancel_job(self):
        record=self.client.start('pdfcontrol_start_workflow',files=['bundle.pdf'])
        self.client.start('pdfcontrol_cancel_job',job_id=record['job_id'])
        record=self.client.wait(record);self.assertEqual(record['status'],'cancelled')
    def test_09_dead_worker_and_compact_status(self):
        folder=PROJECT/'Controle/Octop-PDFCONTROL'/('f'*32);folder.mkdir(exist_ok=True)
        (folder/'job.json').write_text(json.dumps({'project':'123456','job_id':'f'*32,'status':'running','sources_sha256':{},'engine_sha256':{'module':'not-exposed'}}),encoding='utf-8')
        (folder/'worker.pid').write_text('99999999',encoding='ascii')
        record=self.client.start('pdfcontrol_job_status',job_id='f'*32,wait_seconds=0)
        self.assertEqual(record['status'],'failed');self.assertNotIn('engine_sha256',record)

    def test_08_direct_check_with_annotations_in_report(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_check',files=['loose/123456_WA224_V.pdf','loose/123456_WA133_W.pdf'],include_drawings=True))
        self.artifacts(record);self.assertTrue(record['include_drawings']);self.assertTrue(record['engine_fingerprint'])
        with pymupdf.open(record['report']) as doc:
            text='\n'.join(page.get_text() for page in doc);self.assertIn('controlerapport',text.lower())

    def test_10_sources_in_controle_and_verified_delivery(self):
        relative='Controle/Atasamente_nou/123456_TRK300_V.pdf'
        pdf(PROJECT/relative,['Projectnummer: 123456\nVormtekening\nTRK300_V\nDefinitief'])
        fingerprint=digest(PROJECT/relative)
        record=self.client.wait(self.client.start('pdfcontrol_start_check',files=[relative]))
        self.artifacts(record);self.assertTrue(record['delivery_verified'])
        self.assertEqual(record['source_files'],[relative]);self.assertEqual(record['source_count'],1)
        target=PROJECT/'Controle/PDFCONTROL/Rapoarte'
        self.assertTrue(Path(record['report']).is_relative_to(target))
        manifest=json.loads(Path(record['delivery_manifest']).read_text(encoding='utf-8'))
        self.assertEqual(manifest['sources_sha256'],{relative:fingerprint})
        for item in record['artifacts']:self.assertEqual(item['sha256'],digest(Path(item['path'])))
        self.assertEqual(digest(PROJECT/relative),fingerprint)
        with pymupdf.open(record['report']) as report:self.assertIn('Uitvoering gedeeltelijk',report[0].get_text())
        inventory=self.client.start('pdfcontrol_project_files')
        entry=next(row for row in inventory['files'] if row['path']==relative);self.assertTrue(entry['pdf_source'])
        rejected=self.client.call('pdfcontrol_start_check',{'project':'123456','files':[Path(record['report']).relative_to(PROJECT).as_posix()]},error=True)
        self.assertIn('nu sunt surse',rejected)
        self.client.call('pdfcontrol_start_check',{'project':'123456','files':['123456_TRK300_V.pdf']},error=True)
        # A new run must keep the earlier PDF and create a distinct delivery.
        before=digest(Path(record['report']))
        again=self.client.wait(self.client.start('pdfcontrol_start_check',files=[relative]))
        self.assertNotEqual(record['delivery_folder'],again['delivery_folder'])
        self.assertEqual(digest(Path(record['report'])),before)

    def test_11_generated_and_annotated_inputs_rejected(self):
        for relative in ['Controle/checked/source.pdf','Controle/PDFCONTROL/Rapoarte/source.pdf','loose/source_checked_OK.pdf','loose/RAPPORT.pdf']:
            pdf(PROJECT/relative,['Generated output'])
            self.client.call('pdfcontrol_start_check',{'project':'123456','files':[relative]},error=True)

    def test_12_workflow_accepts_explicit_drawings_option(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_workflow',files=['bundle.pdf'],include_drawings=True))
        self.artifacts(record);self.assertTrue(record['delivery_verified']);self.assertTrue(record['include_drawings'])

    def test_13_delivery_failure_is_not_success(self):
        isolated=ROOT/'654321';isolated.mkdir(exist_ok=True)
        pdf(isolated/'source.pdf',['Projectnummer: 654321\nVormtekening\nA1_V\nDefinitief'])
        (isolated/'Controle').mkdir(exist_ok=True)
        (isolated/'Controle/PDFCONTROL').write_text('Deliberately blocked output directory',encoding='ascii')
        record=self.client.call('pdfcontrol_start_check',{'project':'654321','files':['source.pdf']})
        deadline=time.monotonic()+90
        while record['status'] in {'pending','running'}:
            self.assertLess(time.monotonic(),deadline)
            record=self.client.call('pdfcontrol_job_status',{'project':'654321','job_id':record['job_id'],'wait_seconds':2})
        self.assertEqual(record['status'],'failed');self.assertFalse(record.get('delivery_verified',False))

    def test_14_explicit_factory_uses_isolated_original_catalogue(self):
        state=PROJECT/'Controle/PDFCONTROL/project-state.json'
        before=state.read_bytes() if state.exists() else None
        # Exercise catalogue isolation without requiring or deploying private factory data.
        package=OUT/'factory-package';package.mkdir(exist_ok=True)
        for source in PACKAGE.iterdir():
            if source.is_file() and source.suffix in {'.py','.json'}:
                shutil.copy2(source,package/source.name)
        shutil.copytree(PACKAGE/'runtime/ocr-windows-x64',package/'runtime/ocr-windows-x64',dirs_exist_ok=True)
        catalogue=package/'data_lokaal/catalogus_veenoord.json';catalogue.parent.mkdir(exist_ok=True)
        catalogue.write_text(json.dumps({'schema_version':1,'factory':'veenoord','source':'synthetic-fixture.xlsx',
            'source_sha256':hashlib.sha256(b'synthetic factory fixture').hexdigest(),
            'records':[{'code':'MBS_TEST','description':'Synthetic fixture article','category':'import','fields':{},'sheet':'Fixture','row':2}],
            'excluded_rows':[]}),encoding='utf-8')
        client=Client(package)
        try:
            # Probe the freshly copied OCR runtime before the catalogue-isolation operation.
            # This also preserves the detailed loader diagnostic instead of a generic start error.
            status=client.call('pdfcontrol_status')
            self.assertTrue(status['ocr']['available'],json.dumps(status['ocr']))
            record=client.wait(client.start('pdfcontrol_start_check',files=['loose/123456_WA224_V.pdf'],factory='veenoord'))
        finally:
            client.close()
        self.artifacts(record);self.assertTrue(record['catalogue']['available']);self.assertEqual(record['catalogue']['factory'],'veenoord')
        snapshot=Path(record['run_folder'])/'engine/data_lokaal/catalogus_veenoord.json'
        self.assertEqual(digest(snapshot),digest(catalogue))
        self.assertEqual(state.read_bytes() if state.exists() else None,before)
        context=Path(record['run_folder'])/'catalog_context/123456/Controle/PDFCONTROL/project-state.json'
        self.assertEqual(json.loads(context.read_text(encoding='utf-8'))['factory'],'veenoord')

    def test_15_delivery_retries_temporary_windows_lock(self):
        import importlib.util
        spec=importlib.util.spec_from_file_location('delivery_test_bridge',PACKAGE/'octop_mcp.py');bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
        run=PROJECT/'Controle/Octop-PDFCONTROL'/('e'*32);run.mkdir(exist_ok=True)
        report=run/'RAPPORT.pdf';pdf(report,['Only this verified job'])
        original=bridge.os.replace;attempts=[]
        def locked(source,target):
            if Path(source).is_dir():
                attempts.append(1)
                if len(attempts)<3:raise PermissionError('Simulated transient file scanner')
            return original(source,target)
        bridge.os.replace=locked
        record={'project':'123456','job_id':'e'*32,'operation':'check','sources_sha256':{},'report':str(report)}
        try:artifacts=bridge.deliver_outputs(PROJECT,run,record,[{'path':str(report),'pages':1,'bytes':report.stat().st_size}],'done',lambda:False)
        finally:bridge.os.replace=original
        self.assertEqual(len(attempts),3);self.assertTrue(record['delivery_verified']);self.assertEqual(digest(Path(record['report'])),digest(report))

    def test_16_job_status_retries_temporary_read_lock(self):
        import importlib.util
        from unittest.mock import patch
        spec=importlib.util.spec_from_file_location('read_test_bridge',PACKAGE/'octop_mcp.py');bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)
        target=OUT/'read-lock-state.json';target.write_text(json.dumps({'status':'done'}),encoding='utf-8')
        original=Path.read_text;attempts=[]
        def locked(path,*args,**kwargs):
            if path==target:
                attempts.append(1)
                if len(attempts)<3:raise PermissionError('Temporary scanner lock')
            return original(path,*args,**kwargs)
        with patch.object(Path,'read_text',locked):self.assertEqual(bridge.read_record(target)['status'],'done')
        self.assertEqual(len(attempts),3)

    def test_17_abwn_cover_project_and_title_marks(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_extract',files=['abwn-book.pdf']))
        self.artifacts(record);self.assertEqual(record['status'],'partial')
        folder=Path(record['run_folder'])/'engine/PDF_NOU'
        self.assertEqual({p.name for p in folder.glob('123456_*_V.pdf')},{'123456_BA1_V.pdf','123456_BA2_V.pdf'})
        self.assertFalse(list(folder.glob('*_W.pdf')))
        provenance=json.loads(Path(record['provenance']).read_text(encoding='utf-8'))
        extracted=[a for a in provenance['actions'] if a['soort']=='extract']
        self.assertEqual({a['pagina'] for a in extracted},{1,2});self.assertEqual(record['pair_summary']['created'],2)

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Workflows))
    raise SystemExit(0 if result.wasSuccessful() else 1)
