"""Synthetic local audit tests: never touches a production list or calls a model."""
import csv, hashlib, importlib.util, json, sys, tempfile, unittest
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile
ENGINE = Path(__file__).resolve().parents[1]/'pdfcontrol'
sys.path.insert(0,str(ENGINE))
import mbs_artikelcatalogus as reader
spec=importlib.util.spec_from_file_location('import_audit',Path(__file__).with_name('import_audit.py'))
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
HEADER=['Merk','Instortvoorziening','Aantal','Eenheid','Lengte','Tekenbureau','Status tekening']

def xlsx(path, rows, formula=False):
    data=[]
    for i,row in enumerate(rows,1):
        cells=[]
        for j,value in enumerate(row):
            letters='';n=j+1
            while n: n,r=divmod(n-1,26);letters=chr(65+r)+letters
            cells.append('<c r="%s%d" t="inlineStr">%s<is><t>%s</t></is></c>'%(letters,i,'<f>1+1</f>' if formula and i==2 and j==2 else '',escape(str(value))))
        data.append('<row r="%d">'%i+''.join(cells)+'</row>')
    with ZipFile(path,'w') as z:
        z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Fixture" sheetId="1" r:id="s"/></sheets></workbook>')
        z.writestr('xl/_rels/workbook.xml.rels','<Relationships><Relationship Id="s" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+''.join(data)+'</sheetData></worksheet>')

class AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.cat=self.root/'catalogue.json';self.imp=self.root/'import.csv'
        self.data={'factory':'veenoord','records':[{'code':'MBS1','description':'Fixture anchor M20','category':'import','fields':{'eenheid':'st'}}, {'code':'MBS2','description':'Fixture insulation','category':'import','fields':{'eenheid':'m'}}, {'code':'MB1','description':'Billing only','category':'facturare','fields':{'eenheid':'st'}}], 'excluded_rows':[]}
        self.cat.write_text(json.dumps(self.data),encoding='utf-8');self.csv([['TR01','Fixture anchor M20','2','st','','14337','20']])
    def tearDown(self): self.temp.cleanup()
    def csv(self,rows):
        with self.imp.open('w',encoding='utf-8-sig',newline='') as f:csv.writer(f,delimiter=';').writerows([HEADER]+rows)
    def run_audit(self,**changes):
        request={'project':'123456','factory':'veenoord','imports':[str(self.imp)],'catalogue':str(self.cat)};request.update(changes)
        result=audit.run(request,self.root/'reports',ENGINE);return result,json.loads(Path(result['report_json']).read_text(encoding='utf-8'))
    def codes(self,report):return {i['code'] for i in report['issues']}
    def test_sources_unchanged_and_cache_invalidation(self):
        old={p:audit.digest(p) for p in (self.cat,self.imp)};result,report=self.run_audit();self.assertEqual(report['summary']['import_article_rows'],1)
        again,_=self.run_audit();self.assertTrue(again['cached']);self.assertEqual(old,{p:audit.digest(p) for p in old});self.assertLess(len(json.dumps(result)),5000)
        self.csv([['TR01','Fixture anchor M20','3','st','','14337','20']]);changed,_=self.run_audit();self.assertFalse(changed['cached']);self.assertNotEqual(changed['report_json'],result['report_json'])
    def test_factory_isolation_and_missing_catalogue(self):
        with self.assertRaisesRegex(ValueError,'factory'):self.run_audit(factory='soest')
        with self.assertRaises(FileNotFoundError):self.run_audit(catalogue=str(self.root/'absent.json'))
        with self.assertRaises(ValueError):self.run_audit(factory='unknown')
    def test_metres_not_pieces_and_length_required(self):
        self.csv([['TR01','Fixture insulation','1','st','','14337','20']]);_,r=self.run_audit();self.assertTrue({'unit_mismatch','length_required'}<=self.codes(r))
        self.csv([['TR01','Fixture insulation','1','m','1250','14337','20']]);_,r=self.run_audit();self.assertNotIn('length_required',self.codes(r))
    def test_billing_unknown_and_conflict_remain_unresolved(self):
        self.csv([['TR01','Billing only','1','st','','14337','20'],['','Unknown article','1','st','','','']]);_,r=self.run_audit();self.assertEqual(r['summary']['by_code']['article_unresolved'],2)
        self.data['records'].append({'code':'MBS1','description':'Fixture anchor M20','category':'import','fields':{'eenheid':'m'}});self.cat.write_text(json.dumps(self.data));self.csv([['TR01','Fixture anchor M20','2','st','','14337','20']]);_,r=self.run_audit();self.assertIn('article_unresolved',self.codes(r))
    def test_xlsx_formula_and_continuation_row(self):
        p=self.root/'import.xlsx';xlsx(p,[HEADER,['TR01','Fixture anchor M20','2','st','','14337','20'],['','Fixture insulation','1','m','1000','','']],True)
        _,r=self.run_audit(imports=[str(p)]);self.assertEqual([x['merk'] for x in r['records']],['TR01','TR01']);self.assertIn('formula_requires_verification',self.codes(r));self.assertTrue(r['records'][0]['formula_unverified'])
    def test_duplicate_and_malformed_input(self):
        with self.assertRaises(ValueError):self.run_audit(imports=[str(self.imp),str(self.imp)])
        with self.assertRaises(ValueError):self.run_audit(imports=[])
        with self.assertRaises(ValueError):self.run_audit(project='123')
        with self.assertRaises(ValueError):self.run_audit(unrecognized=True)
        self.imp.write_text('wrong;headers\n1;2\n');_,r=self.run_audit();self.assertIn('import_header_not_found',self.codes(r))
    def test_catalogue_xlsx(self):
        p=self.root/'catalogue.xlsx';xlsx(p,[['Artikel','Omschrijving','Eenheid'],['MBS1','Fixture anchor M20','st']]);_,r=self.run_audit(catalogue=str(p));self.assertNotIn('article_unresolved',self.codes(r))
    def test_page_binding_no_wrong_project_or_multiple_marks(self):
        marks={'TR01','TR02'};self.assertEqual(audit.bind_merk('',Path('123456_TR01_V.pdf'),marks,'123456'),'TR01')
        self.assertIsNone(audit.bind_merk('',Path('654321_TR01_V.pdf'),marks,'123456'))
        self.assertIsNone(audit.bind_merk('',Path('123456_TR01_TR02.pdf'),marks,'123456'))
        self.assertIsNone(audit.bind_merk('Merk: TR02',Path('123456_TR01_V.pdf'),marks,'123456'))
    def test_pdf_extractor_evidence_and_quantity(self):
        import pymupdf
        p=self.root/'123456_TR01_V.pdf';doc=pymupdf.open();page=doc.new_page(width=800,height=600)
        for x,text in [(50,'Posnr'),(150,'Benaming'),(600,'Aantal')]:page.insert_text((x,100),text)
        for x,text in [(50,'H01'),(150,'Fixture anchor M20'),(600,'3')]:page.insert_text((x,125),text)
        page.insert_text((623,125),'st',fontname='hebo')
        doc.save(p);doc.close();before=audit.digest(p)
        _,r=self.run_audit(drawings=[str(p)]);self.assertGreater(r['summary']['drawing_table_rows'],0);self.assertIn('quantity_mismatch',self.codes(r));self.assertEqual(audit.digest(p),before)
        item=r['drawing_evidence'][0];self.assertEqual((item['merk'],item['page']),('TR01',1));self.assertTrue(item['bbox'])
    def test_blank_scan_is_partial_not_missing_article(self):
        import pymupdf
        p=self.root/'123456_TR01_V.pdf';d=pymupdf.open();d.new_page();d.save(p);d.close();_,r=self.run_audit(drawings=[str(p)]);self.assertIn('drawing_table_unreadable',self.codes(r));self.assertNotIn('drawing_article_not_found_in_table',self.codes(r));self.assertEqual(r['status'],'review_required')
    def test_drawing_comparison_does_not_sum_versions_or_infer_dimensions(self):
        self.csv([['TR01','Fixture insulation','1','m','1200','14337','20']]);records,_=audit.imports(self.imp,reader);cat=audit.catalog(self.cat,'veenoord',reader);audit.audit_rows(records,cat)
        evidence=[{'path':'123456_TR01_V.pdf','page':1,'merk':'TR01','description':'Fixture insulation L=1200 mm','quantity':'1 m','pos':'G1'}]
        comparisons,issues=audit.compare_drawings(records,evidence,cat);self.assertIn('length_dimension_review',{i['code'] for i in issues});self.assertEqual(comparisons[0]['length_evidence'],[{'value':'1200','unit':'mm'}]);self.assertFalse(comparisons[0]['physical_equivalence_verified'])
    def test_html_escapes_input(self):
        self.csv([['TR01','<script>alert(1)</script>','1','st','','14337','20']]);result,_=self.run_audit();page=Path(result['report_html']).read_text(encoding='utf-8');self.assertNotIn('<script>',page);self.assertIn('&lt;script&gt;',page)

if __name__=='__main__':unittest.main(verbosity=2)
