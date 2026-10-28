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

    def test_18_live_status_alias_is_hidden_but_still_accepted(self):
        listed={t['name'] for t in self.client.request('tools/list',{})['tools']}
        self.assertNotIn('pdfcontrol_live_status',listed);self.assertIn('pdfcontrol_status',listed)
        old=self.client.call('pdfcontrol_live_status');current=self.client.call('pdfcontrol_status')
        self.assertEqual(old['bridge_version'],current['bridge_version']);self.assertEqual(old['ocr']['available'],current['ocr']['available'])
        status=next(t for t in self.client.request('tools/list',{})['tools'] if t['name']=='pdfcontrol_job_status')
        self.assertEqual(status['inputSchema']['properties']['wait_seconds']['maximum'],20);self.assertIn('0-20',status['description']);self.assertNotIn('10 secunde',status['description'])
        self.assertLess(sum(len(json.dumps(t,ensure_ascii=False)) for t in self.client.request('tools/list',{})['tools']),9000)  # 4 read-only tools added in 1.28.0 (+~2.0k)

    # ---- 1.28.0: compact read-only tools, reduced model-facing status -------------------------------------------------
    def fake_job(self,identifier,data=None,silent=None,status='done',**extra):
        folder=PROJECT/'Controle/Octop-PDFCONTROL'/identifier;folder.mkdir(parents=True,exist_ok=True)
        record={'project':'123456','job_id':identifier,'operation':'check','status':status,'sources_sha256':{'a.pdf':'x'},'delivery_verified':True}
        if data is not None:
            target=folder/'engine/checked/resultaten.json';target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps(data),encoding='utf-8');record['results']=str(target)
        if silent is not None:(folder/'stille_fouten.json').write_text(json.dumps({'count':len(silent),'items':silent}),encoding='utf-8')
        record.update(extra);(folder/'job.json').write_text(json.dumps(record),encoding='utf-8');return folder
    def drawings(self,count):
        return {'tekeningen':[{'bestand':f'123456_X{n}_W.pdf','status':'NIET_OK' if n%2 else 'OK','tekeningstatus':{'ok':n%3!=0,'tekst':'STATUS: concept; niet definitief.'},
            'artikelen':[{'pos':1,'omschrijving':'Artikel A','status':'OK','note':''},{'pos':2,'omschrijving':'Artikel B','status':'NIET_OK','note':'afwijkend'}],
            'bevindingen':[{'code':'CODE_%d'%(n%4),'ernst':'FOUT' if n%2 else 'WAARSCHUWING','artikel':'Artikel B','tekst':'Bevinding %d '%n+'x'*120}]} for n in range(count)]}

    def test_19_model_status_has_no_pids_or_paths_but_diagnostics_stay_local(self):
        status=self.client.call('pdfcontrol_status');text=json.dumps(status,ensure_ascii=False)
        for forbidden in ('server_pid','trace_file','adapter_file','trace_error','diagnostic_id','package','engine_manifest','executable','python','pymupdf'):self.assertNotIn(forbidden,text)
        self.assertNotIn(str(self.client.process.pid),text);self.assertNotIn(str(PACKAGE),text);self.assertNotIn(str(Path(sys.executable).parent),text)
        def strings(value):
            if isinstance(value,dict):
                for k,v in value.items():yield k;yield from strings(v)
            elif isinstance(value,list):
                for v in value:yield from strings(v)
            elif isinstance(value,str):yield value
        for value in strings(dict(status,projects_root='')):self.assertNotRegex(value,r'^[A-Za-z]:|\\|^/',value)
        self.assertEqual(status['projects_root'],str(ROOT));self.assertTrue(status['ocr']['available']);self.assertEqual(status['ocr']['missing_languages'],[])
        local=json.loads((PACKAGE/'runtime/octop_status_diagnostic.json').read_text(encoding='utf-8'))
        self.assertEqual(local['server_pid'],self.client.process.pid);self.assertTrue(local['trace_file']);self.assertTrue(local['adapter_file'])
        shown=subprocess.run([sys.executable,'-I','-B',str(PACKAGE/'octop_mcp.py'),'--self-test'],capture_output=True,text=True,encoding='utf-8',
            env=dict(os.environ,PDFCONTROL_PACKAGE=str(PACKAGE),PDFCONTROL_PROJECTS_ROOT=str(ROOT)),timeout=60)
        self.assertIn('server_pid',shown.stdout);self.assertIn('trace_file',shown.stdout)

    def test_20_summary_and_findings_of_a_real_check_job(self):
        record=self.client.wait(self.client.start('pdfcontrol_start_check',files=['loose/123456_WA224_V.pdf','loose/123456_WA133_W.pdf']))
        summary=self.client.start('pdfcontrol_job_summary',job_id=record['job_id'])
        self.assertLess(len(json.dumps(summary)),3500);self.assertEqual(summary['status'],record['status']);self.assertTrue(summary['delivery_verified'])
        self.assertEqual(summary['report'],record['report']);self.assertEqual(summary['delivery_folder'],record['delivery_folder']);self.assertFalse(summary['transfer_approved'])
        self.assertEqual(summary['counters']['drawings'],2)
        engine=json.loads(Path(record['results']).read_text(encoding='utf-8'))
        expected=sum(len(d.get('bevindingen',[])) for d in engine['tekeningen'])
        full=self.client.start('pdfcontrol_job_findings',job_id=record['job_id'],max_chars=12000)
        self.assertGreaterEqual(full['total'],expected);self.assertEqual(full['total'],summary['findings_total'])
        if full['next_offset'] is None:
            for d in engine['tekeningen']:
                for f in d.get('bevindingen',[]):self.assertTrue(any(i['file']==d['bestand'] and i['code']==f['code'] and i['text']==f['tekst'][:299] or i['text']==f['tekst'] for i in full['items']),f)
        pages=[];offset=0
        while offset is not None:
            page=self.client.start('pdfcontrol_job_findings',job_id=record['job_id'],offset=offset,max_chars=500);pages+=page['items'];offset=page['next_offset']
            self.assertLessEqual(len(json.dumps(page)),1500)
        self.assertEqual(pages,full['items'])
        if full['items']:
            code=full['items'][0]['code'];only=self.client.start('pdfcontrol_job_findings',job_id=record['job_id'],filter=code.lower(),max_chars=12000)
            self.assertTrue(only['items']);self.assertTrue(all(code.lower() in json.dumps(i).lower() for i in only['items']));self.assertLessEqual(only['matched'],full['total'])
        self.assertEqual(self.client.start('pdfcontrol_job_findings',job_id=record['job_id'],filter='zzzz-nothing-zzzz')['items'],[])

    def test_21_findings_are_paginated_capped_and_never_invented(self):
        identifier='a'*32;self.fake_job(identifier,self.drawings(60),[{'blad':'123456_X1_W','waar':'zoek_labels','soort':'AttributeError','boodschap':'NoneType'}])
        summary=self.client.start('pdfcontrol_job_summary',job_id=identifier)
        self.assertEqual(summary['counters']['drawings'],60);self.assertEqual(summary['silent_checks'],1);self.assertEqual(summary['counters']['by_severity']['SILENT'],1)
        self.assertEqual(summary['counters']['drawing_status']['not_final'],20);self.assertEqual(len(summary['counters']['top_codes']),7);self.assertEqual(summary['findings_total'],141)
        seen=[];offset=0;calls=0
        while offset is not None:
            page=self.client.start('pdfcontrol_job_findings',job_id=identifier,offset=offset,max_chars=2000);calls+=1;seen+=page['items'];offset=page['next_offset']
            self.assertLessEqual(len(json.dumps(page,ensure_ascii=False)),2100)
        self.assertGreater(calls,5);self.assertEqual(len(seen),summary['findings_total']);self.assertEqual(len({json.dumps(i) for i in seen}),len(seen))
        silent=self.client.start('pdfcontrol_job_findings',job_id=identifier,filter='silent');self.assertEqual([i['code'] for i in silent['items']],['SILENT_zoek_labels'])
        both=self.client.start('pdfcontrol_job_findings',job_id=identifier,filter='code_1 fout',max_chars=12000)
        self.assertTrue(both['items'] and all(i['code']=='CODE_1' and i['sev']=='FOUT' for i in both['items']))
        beyond=self.client.start('pdfcontrol_job_findings',job_id=identifier,offset=10000);self.assertEqual(beyond['items'],[]);self.assertIsNone(beyond['next_offset'])
        # No output, no running state, a result outside the project: nothing is invented.
        self.fake_job('b'*32,None);none=self.client.start('pdfcontrol_job_findings',job_id='b'*32);self.assertEqual((none['total'],none['items']),(0,[]));self.assertIn('note',none)
        self.assertNotIn('counters',self.client.start('pdfcontrol_job_summary',job_id='b'*32))
        self.fake_job('c'*32,None,status='running');running=self.client.start('pdfcontrol_job_findings',job_id='c'*32);self.assertEqual(running['items'],[]);self.assertIn('job_status',running['note'])
        outside=OUT/'outside-results.json';outside.write_text(json.dumps(self.drawings(3)),encoding='utf-8')
        self.fake_job('d'*32,None,results=str(outside));escaped=self.client.start('pdfcontrol_job_findings',job_id='d'*32);self.assertEqual(escaped['items'],[])
        self.assertNotIn('counters',self.client.start('pdfcontrol_job_summary',job_id='d'*32))
        broken=self.fake_job('e'*32,None);(broken/'engine/checked').mkdir(parents=True,exist_ok=True);(broken/'engine/checked/resultaten.json').write_text('{not json',encoding='utf-8')
        self.assertEqual(self.client.start('pdfcontrol_job_findings',job_id='e'*32)['items'],[])
        failed=self.fake_job('1'*32,None,status='running');(failed/'worker.pid').write_text('99999999',encoding='ascii')
        self.assertEqual(self.client.start('pdfcontrol_job_summary',job_id='1'*32)['status'],'failed')

    def test_22_new_tools_reject_bad_arguments_and_are_read_only(self):
        tools={t['name']:t for t in self.client.request('tools/list',{})['tools']}
        for name in ('pdfcontrol_job_summary','pdfcontrol_job_findings','pdfcontrol_list_projects','pdfcontrol_catalogue_status','pdfcontrol_status'):
            self.assertTrue(tools[name]['annotations']['readOnlyHint'],name)
        for name in tools:
            if name.startswith('pdfcontrol_start_') or name in {'pdfcontrol_cancel_job','pdfcontrol_regenerate_report'}:self.assertFalse(tools[name]['annotations']['readOnlyHint'],name)
        good='a'*32;self.fake_job(good,self.drawings(2))
        for name,args in [('pdfcontrol_job_summary',{'project':'123456','job_id':'../x'}),('pdfcontrol_job_summary',{'project':'12345','job_id':good}),('pdfcontrol_job_summary',{'project':'123456'}),
                          ('pdfcontrol_job_findings',{'project':'123456','job_id':good,'offset':-1}),('pdfcontrol_job_findings',{'project':'123456','job_id':good,'max_chars':10}),
                          ('pdfcontrol_job_findings',{'project':'123456','job_id':good,'max_chars':50000}),('pdfcontrol_job_findings',{'project':'123456','job_id':good,'filter':'x'*81}),
                          ('pdfcontrol_job_findings',{'project':'123456','job_id':good,'path':'x'}),('pdfcontrol_job_findings',{'project':'123456','job_id':'9'*32}),
                          ('pdfcontrol_list_projects',{'offset':-5}),('pdfcontrol_list_projects',{'offset':'1'}),('pdfcontrol_list_projects',{'root':'C:/'}),
                          ('pdfcontrol_catalogue_status',{}),('pdfcontrol_catalogue_status',{'factory':'mars'}),('pdfcontrol_catalogue_status',{'factory':'soest','article':''}),
                          ('pdfcontrol_catalogue_status',{'factory':'soest','project':'abc'}),('pdfcontrol_status',{'verbose':True})]:
            self.client.call(name,args,error=True)
        # Writes never happen: the whole project tree is unchanged by read-only calls.
        before={str(p):p.stat().st_mtime_ns for p in PROJECT.rglob('*') if p.is_file()}
        self.client.start('pdfcontrol_job_summary',job_id=good);self.client.start('pdfcontrol_job_findings',job_id=good);self.client.call('pdfcontrol_list_projects');self.client.call('pdfcontrol_catalogue_status',{'factory':'soest'})
        self.assertEqual(before,{str(p):p.stat().st_mtime_ns for p in PROJECT.rglob('*') if p.is_file()})

    def test_23_list_projects_is_paginated_and_never_infers_factory(self):
        created=[ROOT/('9%05d'%n) for n in range(60)]
        try:
            for folder in created:folder.mkdir(exist_ok=True)
            state=lambda number,data:((ROOT/number/'Controle/PDFCONTROL').mkdir(parents=True,exist_ok=True),(ROOT/number/'Controle/PDFCONTROL/project-state.json').write_text(json.dumps(data),encoding='utf-8'))
            state('900001',{'project':'900001','factory':'MBS Veenoord'});state('900002',{'project':'900002','destinations':[{'factory':'soest'},{'factory':'alphen'}]})
            state('900003',{'project':'900003','destinations':[{'factory':'lelystad'}]});state('900004',{'project':'999999','factory':'soest'})
            first=self.client.call('pdfcontrol_list_projects');self.assertEqual(len(first['items']),50);self.assertEqual(first['next_offset'],50);self.assertGreaterEqual(first['total'],61)
            names=[i['project'] for i in first['items']];self.assertEqual(names,sorted(names,reverse=True))
            second=self.client.call('pdfcontrol_list_projects',{'offset':50});self.assertTrue(second['items']);self.assertIsNone(second['next_offset'])
            every={i['project']:i for i in first['items']+second['items']}
            self.assertEqual(every['900001']['factory'],'veenoord');self.assertIsNone(every['900002']['factory']);self.assertEqual(every['900003']['factory'],'lelystad')
            self.assertIsNone(every['900004']['factory']);self.assertIsNone(every['900010']['factory']);self.assertIn('123456',every);self.assertGreaterEqual(every['123456']['jobs'],1)
            self.assertNotIn(str(ROOT),json.dumps(first))
        finally:
            for folder in created:shutil.rmtree(folder,ignore_errors=True)

    def test_24_catalogue_status_is_factory_scoped_and_read_only(self):
        base=OUT/'catalogue-case';shutil.rmtree(base,ignore_errors=True);package=base/'pdfcontrol';package.mkdir(parents=True)
        for source in PACKAGE.iterdir():
            if source.is_file() and source.suffix in {'.py','.json'}:shutil.copy2(source,package/source.name)
        source_file=base/'factory-source.xlsx';source_file.write_bytes(b'changed after snapshot')
        record=lambda code,text,category='import':{'code':code,'description':text,'category':category,'fields':{'eenheid':'stuks'},'sheet':'S','row':2}
        catalogue={'schema_version':1,'factory':'veenoord','source':str(source_file),'source_sha256':hashlib.sha256(b'original').hexdigest(),
            'records':[record('MBS_A','Synthetic fixture article'),record('MBS_B','Other article'),record('MBS_B','Other article renamed'),record('MB_C','Invoice only','facturare')],'excluded_rows':[]}
        (package/'data_lokaal').mkdir();(package/'data_lokaal/catalogus_veenoord.json').write_text(json.dumps(catalogue),encoding='utf-8')
        rule=lambda factory,article,**x:dict({'factory':factory,'article':article,'calculation':'pir_area_as_fast_length','units_per_metre':1000,'rounding':'half_down'},**x)
        (base/'fast-import-rules.json').write_text(json.dumps({'rules':[rule('soest','MBS_PIR'),rule('veenoord','MBS_A'),rule('veenoord','MBS_A'),rule('lelystad','MBS_L',rounding='up')]}),encoding='utf-8')
        folder=ROOT/'900101';(folder/'Controle/PDFCONTROL').mkdir(parents=True,exist_ok=True);(folder/'Controle/PDFCONTROL/project-state.json').write_text(json.dumps({'project':'900101','factory':'veenoord'}),encoding='utf-8')
        (ROOT/'900102').mkdir(exist_ok=True);before=digest(package/'data_lokaal/catalogus_veenoord.json')
        client=Client(package)
        try:
            ve=client.call('pdfcontrol_catalogue_status',{'factory':'veenoord','project':'900101','article':'synthetic fixture article'})
            self.assertTrue(ve['catalogue']['available']);self.assertEqual((ve['catalogue']['records'],ve['catalogue']['import_records'],ve['catalogue']['import_codes']),(4,3,2))
            self.assertEqual(ve['catalogue']['conflict_codes'],1);self.assertFalse(ve['catalogue']['source_matches']);self.assertIn('warning',ve['catalogue'])
            self.assertEqual(ve['project'],{'project':'900101','confirmed_factory':'veenoord','matches':True})
            self.assertEqual(ve['lookup']['status'],'catalogus_uniek');self.assertTrue(ve['lookup']['normalisation_allowed']);self.assertFalse(ve['lookup']['physical_equivalence_verified'])
            self.assertEqual(ve['lookup']['matches'][0]['code'],'MBS_A');self.assertFalse(ve['fast_rules']['unambiguous']);self.assertFalse(ve['fast_rules']['ready'])
            unknown=client.call('pdfcontrol_catalogue_status',{'factory':'veenoord','article':'Not in the catalogue'})
            self.assertEqual(unknown['lookup']['status'],'niet_gevonden');self.assertFalse(unknown['lookup']['normalisation_allowed'])
            conflict=client.call('pdfcontrol_catalogue_status',{'factory':'veenoord','article':'Other article'});self.assertEqual(conflict['lookup']['status'],'catalogus_conflict');self.assertFalse(conflict['lookup']['normalisation_allowed'])
            soest=client.call('pdfcontrol_catalogue_status',{'factory':'soest','project':'900101','article':'Synthetic fixture article'})
            self.assertFalse(soest['catalogue']['available']);self.assertEqual(soest['project']['matches'],False);self.assertEqual(soest['lookup']['status'],'catalog_indisponibil')
            self.assertEqual(soest['fast_rules']['articles'],['MBS_PIR']);self.assertTrue(soest['fast_rules']['ready'])
            self.assertFalse(client.call('pdfcontrol_catalogue_status',{'factory':'lelystad'})['fast_rules']['ready'])
            self.assertIsNone(client.call('pdfcontrol_catalogue_status',{'factory':'alphen','project':'900102'})['project']['confirmed_factory'])
            self.assertNotIn(json.dumps(str(base))[1:-1],json.dumps([ve,soest]))
        finally:client.close();shutil.rmtree(folder,ignore_errors=True);shutil.rmtree(ROOT/'900102',ignore_errors=True)
        self.assertEqual(digest(package/'data_lokaal/catalogus_veenoord.json'),before)

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(Workflows))
    raise SystemExit(0 if result.wasSuccessful() else 1)
