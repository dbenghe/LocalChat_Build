"""Synthetic PDF/table/catalogue/XLSX tests. No model or production source."""
import importlib.util, json, sys, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
ENGINE=Path(__file__).resolve().parents[1]/'pdfcontrol'
sys.path[:0]=[str(Path(__file__).parent),str(ENGINE)]
import drawing_import as tool
from test_import_audit import xlsx
import pymupdf
HEAD=['Merk','Hoofdregel','Aantal','Lengte','Breedte','Hoogte','Dikte','Inhoud','Instortvoorziening','Instortvoorziening Merk','Eenheid','Tekenbureau','Status tekening']

class DrawingImportTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  self.cat=self.root/'catalogue.json';self.template=self.root/'template.xlsx';self.pdf=self.root/'123456_TR01_V.pdf'
  self.cat.write_text(json.dumps({'factory':'soest','records':[{'code':'MBS1','description':'Fixture anchor M20','category':'import','fields':{'eenheid':'st'}},{'code':'MB1','description':'Billing anchor','category':'facturare','fields':{'eenheid':'st'}}]}))
  xlsx(self.template,[HEAD,['OLD','999','9','','','','','','Old article']])
  self.make_pdf()
 def tearDown(self):self.temp.cleanup()
 def make_pdf(self,scan=False):
  doc=pymupdf.open();p=doc.new_page(width=800,height=600)
  p.insert_text((50,40),'Merk: TR01')
  for i,t in enumerate(['Lengte: 1.2 m','Breedte: 800 mm','Hoogte: 900 mm','Dikte: 150 mm','Volume: 0.123 m3','Aantal elementen: 2']):p.insert_text((50,170+i*20),t)
  for x,t in [(50,'Posnr'),(150,'Benaming'),(600,'Aantal')]:p.insert_text((x,100),t)
  for x,t in [(50,'H01'),(150,'Fixture anchor M20'),(600,'3')]:p.insert_text((x,125),t)
  p.insert_text((623,125),'st',fontname='hebo')
  if scan:
   pix=p.get_pixmap(matrix=pymupdf.Matrix(2,2));other=pymupdf.open();op=other.new_page(width=800,height=600);op.insert_image(op.rect,pixmap=pix);doc.close();doc=other
  doc.save(self.pdf);doc.close()
 def run_tool(self,**kw):
  req={'project':'123456','factory':'soest','drawings':[str(self.pdf)],'catalogue':str(self.cat),'ocr':False};req.update(kw)
  result=tool.run(req,self.root/'reports',ENGINE);report=json.loads(Path(result['report_json']).read_text(encoding='utf-8'));return result,report
 def test_table_catalogue_template_and_dimensions(self):
  before={p:tool.audit.digest(p) for p in [self.cat,self.pdf,self.template]}
  result,report=self.run_tool(template=str(self.template),output=str(self.root/'draft.xlsx'),hoofdregel='70')
  self.assertEqual((result['elements'],result['articles']),(1,1));self.assertEqual(report['records'][0]['fields']['Lengte'],'1200')
  self.assertEqual(report['records'][0]['fields']['Hoogte'],'900');self.assertEqual(report['records'][0]['fields']['Dikte'],'150');self.assertEqual(report['records'][0]['fields']['Inhoud'],'0.123')
  article=report['records'][1];self.assertEqual(article['fields']['Aantal'],'3');self.assertEqual(article['fields']['Instortvoorziening'],'Fixture anchor M20');self.assertNotIn('H01',article['fields']['Instortvoorziening Merk']);self.assertEqual(article['source']['pos'],'H01')
  self.assertEqual(article['source']['catalogue_codes'],['MBS1']);self.assertEqual(article['source']['page'],1);self.assertTrue(article['source']['bbox'])
  self.assertTrue(Path(result['output']).is_file());self.assertFalse(result['ready_for_isah']);self.assertEqual(result['api_requests'],0)
  self.assertEqual(before,{p:tool.audit.digest(p) for p in before})
  import mbs_artikelcatalogus as reader
  rows=list(reader.xlsx_bladen(result['output']))[0][1];self.assertEqual(rows[0]['cells'],HEAD);self.assertEqual(rows[1]['cells'][0],'TR01');self.assertNotIn('OLD',str(rows))
 def test_unchanged_batch_reused_and_changed_output_rejected(self):
  out=self.root/'cached.xlsx';first,_=self.run_tool(template=str(self.template),output=str(out),hoofdregel='70')
  with patch('drawing_import.read_pages',side_effect=AssertionError('unchanged batch rerun')):
   again,_=self.run_tool(template=str(self.template),output=str(out),hoofdregel='70');self.assertTrue(again['cached']);self.assertEqual(first['report_json'],again['report_json'])
  out.write_bytes(b'changed')
  with self.assertRaisesRegex(ValueError,'changed'):self.run_tool(template=str(self.template),output=str(out),hoofdregel='70')
 def test_no_geometry_guess_and_conflicting_dimensions(self):
  fields,_,issues=tool.labelled_fields('Lengte: 1200 mm\nLengte: 1300 mm\nHoogte: 900 mm\nDikte plaat: 150 mm\nTotaal dikte: 200 mm')
  self.assertNotIn('Lengte',fields);self.assertNotIn('Dikte',fields);self.assertNotIn('Inhoud',fields);self.assertEqual(fields['Dikte plaat'],'150');self.assertEqual(issues[0]['code'],'dimension_conflict')
  result,report=self.run_tool();self.assertIn('hoofdregel_required',result['issue_counts']);self.assertIsNone(result['output']);self.assertFalse(report['ready_for_isah'])
 def test_factory_wrong_project_and_duplicate_sources(self):
  with self.assertRaisesRegex(ValueError,'factory'):self.run_tool(factory='veenoord')
  with self.assertRaisesRegex(ValueError,'another project'):self.run_tool(project='654321')
  with self.assertRaisesRegex(ValueError,'Duplicate'):self.run_tool(drawings=[str(self.pdf),str(self.pdf)])
 def test_v_w_share_positions_without_double_counting_and_add_unique_rows(self):
  pages,_=tool.read_pages([self.pdf],'123456',False);import copy,mbs_artikelcatalogus as reader
  w=copy.deepcopy(pages[0]);w['role']='W';w['articles'].append(dict(w['articles'][0],pos='H02',quantity='1'))
  records,issues=tool.make_records(pages+[w],tool.audit.catalog(self.cat,'soest',reader))
  self.assertEqual(len(records),3);self.assertEqual([r['fields'].get('Aantal') for r in records],['2','3','1'])
  w['articles'][0]['quantity']='8';_,issues=tool.make_records(pages+[w],tool.audit.catalog(self.cat,'soest',reader));self.assertIn('article_position_conflict',{i['code'] for i in issues})
 def test_explicit_reviewed_mark_binding_keeps_provenance(self):
  binding={'path':str(self.pdf),'page':1,'merk':'TR02','evidence':'Synthetic reviewed title mark TR02'}
  result,report=self.run_tool(mark_bindings=[binding]);self.assertEqual(report['records'][0]['fields']['Merk'],'TR02');self.assertEqual(report['pages'][0]['mark_binding']['detected_mark'],'TR01');self.assertIn('mark_binding_review_required',result['issue_counts'])
  with self.assertRaisesRegex(ValueError,'does not exist'):self.run_tool(mark_bindings=[dict(binding,page=2)])
 def test_reviewed_fields_bulk_preserves_evidence_and_validates_names(self):
  reviewed=[{'merk':'TR01','fields':{'Lengte':'1350','Hoofdregel':'70','Mengsel':'C30/37'},'evidence':'Synthetic drawing section A, overall concrete length 1350 mm and project decision'}]
  result,report=self.run_tool(reviewed_rows=reviewed,template=str(self.template),output=str(self.root/'reviewed.xlsx'))
  row=report['records'][0];self.assertEqual(row['fields']['Lengte'],'1350');self.assertEqual(row['fields']['Mengsel'],'C30/37');self.assertEqual(row['reviewed_changes'][0]['changes'][0]['old'],'1200');self.assertIn('reviewed_source_verification_required',result['issue_counts']);self.assertNotIn('hoofdregel_required',result['issue_counts'])
  with self.assertRaisesRegex(ValueError,'positive'):self.run_tool(reviewed_rows=[dict(reviewed[0],fields={'Lengte':'-5'})])
  with self.assertRaisesRegex(ValueError,'unambiguous'):self.run_tool(reviewed_rows=[{'merk':'TR01','pos':'H01','fields':{'Instortvoorziening':'Billing anchor'},'evidence':'Synthetic test rejects MB billing item'}])
 def test_volume_arithmetic_per_piece_not_element_count(self):
  pages,_=tool.read_pages([self.pdf],'123456',False);pages[0]['fields'].pop('Inhoud');import mbs_artikelcatalogus as reader
  records,issues=tool.make_records(pages,tool.audit.catalog(self.cat,'soest',reader))
  geometry=[{'merk':'TR01','evidence':'Synthetic shape: solid box minus opening confirmed by section A','components':[{'shape':'box','dimensions_mm':['1000','1000','1000']},{'shape':'box','dimensions_mm':['100','200','1000'],'subtract':True}]}]
  tool.apply_reviewed(records,issues,[],geometry,tool.audit.catalog(self.cat,'soest',reader));self.assertEqual(records[0]['fields']['Inhoud'],'0.98');self.assertEqual(records[0]['fields']['Aantal'],'2');self.assertEqual(records[0]['geometry_calculation']['net_m3'],'0.98');self.assertFalse(records[0]['geometry_calculation']['shape_interpretation_independently_verified'])
  geometry[0]['components']=[{'shape':'triangular_prism','dimensions_mm':['1000','1000','1000']}];records[0]['fields'].pop('Inhoud');tool.apply_reviewed(records,issues,[],geometry,tool.audit.catalog(self.cat,'soest',reader));self.assertEqual(records[0]['fields']['Inhoud'],'0.5')
  geometry[0]['components']=[{'shape':'cylinder','dimensions_mm':['1000','1000']}];records[0]['fields'].pop('Inhoud');tool.apply_reviewed(records,issues,[],geometry,tool.audit.catalog(self.cat,'soest',reader));self.assertTrue(records[0]['fields']['Inhoud'].startswith('0.785398'))
 def test_printed_volume_conflict_and_invalid_geometry(self):
  geometry=[{'merk':'TR01','evidence':'Synthetic reviewed box conflicts with printed volume','components':[{'shape':'box','dimensions_mm':['1000','1000','1000']}]}]
  result,report=self.run_tool(geometry=geometry);self.assertEqual(report['records'][0]['fields']['Inhoud'],'0.123');self.assertIn('printed_calculated_volume_conflict',result['issue_counts'])
  geometry[0]['components'][0]['subtract']=True
  with self.assertRaisesRegex(ValueError,'net volume'):self.run_tool(geometry=geometry)
 def test_unit_bearing_title_cells_and_encoding(self):
  d=pymupdf.open();p=d.new_page(width=800,height=600)
  p.insert_text((520,490),'Volume:');p.insert_text((520,505),'4.17 m3');p.insert_text((650,475),'Aantal:');p.insert_text((710,475),'1 st')
  fields,evidence=tool.title_cells(p);self.assertEqual(fields,{'Inhoud':'4.17','Aantal':'1'});self.assertEqual(evidence['Inhoud']['method'],'labelled_title_cell');d.close()
  self.assertEqual(tool.repair_encoding('Gain \u00c3\u00b842'),'Gain \u00f842');self.assertEqual(tool.repair_encoding('Gain \u00d842'),'Gain \u00d842')
 def test_actual_net_gross_columns_never_silently_duplicate(self):
  real=self.root/'real-schema.xlsx';headers=['Tekenbureau','Hoofdregel','Fase','Merk','Status tekening','Instortvoorziening','Aantal','Lengte','Breedte','Dikte','Netto m2','Bruto m2','Mengsel','Netto m3','Bruto m3','Instortvoorziening Merk','Gewicht(kg)'];xlsx(real,[headers])
  result,report=self.run_tool(template=str(real),output=str(self.root/'mapped.xlsx'),column_map={'Inhoud':'Netto m3'},hoofdregel='70')
  self.assertEqual(report['records'][0]['fields']['Netto m3'],'0.123');self.assertNotIn('Bruto m3',report['records'][0]['fields']);self.assertEqual(result['template_columns'],headers)
  import mbs_artikelcatalogus as reader
  rows=list(reader.xlsx_bladen(result['output']))[0][1];self.assertEqual(rows[1]['cells'][13],'0.123');self.assertTrue(len(rows[1]['cells'])<=14 or rows[1]['cells'][14]=='')
  result,_=self.run_tool(template=str(real),output=str(self.root/'unmapped.xlsx'));self.assertIn('volume_column_mapping_required',result['issue_counts'])
  with self.assertRaisesRegex(ValueError,'mapping'):self.run_tool(column_map={'Inhoud':'Netto m2'})
 def test_plan_area_arithmetic_and_piece_counts(self):
  geometry=[{'merk':'TR01','column':'Netto m2','evidence':'Synthetic plan area from section: rectangle minus rectangular opening','components':[{'shape':'rectangle','dimensions_mm':['2000','1000']},{'shape':'rectangle','dimensions_mm':['200','100'],'subtract':True}]}]
  _,report=self.run_tool(geometry=geometry);self.assertEqual(report['records'][0]['fields']['Netto m2'],'1.98');self.assertEqual(report['records'][0]['fields']['Aantal'],'2')
  geometry[0]['components'][0]['shape']='box'
  with self.assertRaisesRegex(ValueError,'target unit'):self.run_tool(geometry=geometry)
  with self.assertRaisesRegex(ValueError,'integers'):self.run_tool(reviewed_rows=[{'merk':'TR01','fields':{'Aantal':'2.5'},'evidence':'Synthetic invalid fractional element count'}])
 def test_template_and_output_validation(self):
  with self.assertRaisesRegex(ValueError,'both'):self.run_tool(output=str(self.root/'x.xlsx'))
  with self.assertRaisesRegex(ValueError,'new distinct'):self.run_tool(template=str(self.template),output=str(self.template))
  wrong=self.root/'wrong.xlsx';xlsx(wrong,[['unrelated'],['data']])
  with self.assertRaisesRegex(ValueError,'header'):self.run_tool(template=str(wrong),output=str(self.root/'x.xlsx'))
  self.assertFalse((self.root/'x.xlsx').exists())
 def test_missing_unknown_and_no_cross_factory_alias(self):
  data=json.loads(self.cat.read_text());data['records'][0]['description']='Other M20';self.cat.write_text(json.dumps(data))
  result,report=self.run_tool();self.assertIn('article_unresolved',result['issue_counts']);self.assertEqual(report['records'][1]['fields']['Instortvoorziening'],'Fixture anchor M20');self.assertNotIn('catalogue_codes',report['records'][1]['source'])
 def test_quantity_not_guessed_or_summed(self):
  pages,_=tool.read_pages([self.pdf],'123456',False);pages[0]['articles'][0]['quantity']='?';import mbs_artikelcatalogus as reader
  records,issues=tool.make_records(pages+pages,tool.audit.catalog(self.cat,'soest',reader))
  self.assertIn('duplicate_mark_source',{i['code'] for i in issues});self.assertIn('article_quantity_unresolved',{i['code'] for i in issues});self.assertNotIn('Aantal',records[1]['fields']);self.assertEqual(len(records),2)
 def test_w_not_double_counted_and_title_conflict(self):
  pages,_=tool.read_pages([self.pdf],'123456',False);pages[0]['role']='W';import mbs_artikelcatalogus as reader
  records,issues=tool.make_records(pages,tool.audit.catalog(self.cat,'soest',reader));self.assertEqual(len(records),2);self.assertIn('form_drawing_required',{i['code'] for i in issues})
  self.assertIsNone(tool.identity('Merk: TR02',self.pdf,'123456',True)[0])
 def test_ocr_invoked_on_unreadable_table_without_source_write(self):
  # Actual OCR is covered separately; this tests fallback/error handling deterministically.
  self.pdf.unlink();self.make_pdf(scan=True);before=tool.audit.digest(self.pdf)
  with patch('mbs_ocr_artikelen.aanvullen',side_effect=RuntimeError('synthetic OCR unavailable')) as invoke:
   result,_=self.run_tool(ocr=True);self.assertEqual(invoke.call_count,1);self.assertIn('ocr_failed',result['issue_counts']);self.assertIn('drawing_table_unreadable',result['issue_counts'])
  self.assertEqual(before,tool.audit.digest(self.pdf))
 def test_article_named_dimension_belongs_to_accessory(self):
  pages,_=tool.read_pages([self.pdf],'123456',False);pages[0]['articles'][0]['description']='Fixture anchor M20 L=60 cm';import mbs_artikelcatalogus as reader
  records,_=tool.make_records(pages,tool.audit.catalog(self.cat,'soest',reader));self.assertEqual(records[1]['fields']['Lengte'],'600');self.assertEqual(records[0]['fields']['Lengte'],'1200')
 def test_no_numeric_formula_injection(self):
  records=[{'fields':{'Merk':'=HYPERLINK("bad")','Aantal':'2'},'source':{},'kind':'element'}]
  out=self.root/'safe.xlsx';tool.build_xlsx(self.template,out,records)
  import zipfile
  with zipfile.ZipFile(out) as z:self.assertNotIn(b'<f>',z.read('xl/worksheets/sheet1.xml'))

if __name__=='__main__':unittest.main(verbosity=2)
