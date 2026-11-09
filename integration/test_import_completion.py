import sys,json,unittest,copy
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import test_drawing_import as fixture_module
import drawing_import as tool
class CompletionTests(unittest.TestCase):
 def setUp(self):self.fixture=fixture_module.DrawingImportTests();self.fixture.setUp()
 def tearDown(self):self.fixture.tearDown()
 def run_tool(self,reviewed):return self.fixture.run_tool(template=str(self.fixture.template),output=str(self.fixture.root/'completed.xlsx'),hoofdregel='70',reviewed_rows=reviewed)
 def main(self):return dict(merk='TR01',fields={'Instortvoorziening':'Synthetic concrete element','Mengsel':'MBS-B24'},evidence='Synthetic fixture title and explicit project decision')
 def extra(self):return dict(merk='TR01',pos='H02',create=True,source=dict(path=str(self.fixture.pdf.resolve()),page=1),fields={'Instortvoorziening':'Fixture anchor M20','Aantal':'2'},evidence='Fixture page 1 omitted table row H02 reviewed')
 def test_omitted_article_and_primary_type(self):
  result,report=self.run_tool([self.main(),self.extra()]);self.assertEqual((result['elements'],result['articles']),(1,2));self.assertEqual(report['records'][0]['fields']['Instortvoorziening'],'Synthetic concrete element');self.assertEqual(report['records'][-1]['fields']['Aantal'],'2');self.assertTrue(result['completeness']['required_values_present']);self.assertFalse(result['ready_for_isah']);self.assertEqual(report['records'][-1]['source']['catalogue_codes'],['MBS1'])
 def test_missing_mix_is_not_complete(self):
  result,report=self.fixture.run_tool();self.assertFalse(result['completeness']['required_values_present']);self.assertIn({'merk':'TR01','field':'Mengsel'},result['completeness']['missing'])
 def test_duplicate_or_wrong_source_refused(self):
  for item in [dict(self.extra(),pos='H01'),dict(self.extra(),source=dict(path='outside.pdf',page=1)),dict(self.extra(),source=dict(path=str(self.fixture.pdf),page=2)),dict(self.extra(),merk='OTHER')]:
   with self.subTest(item=item),self.assertRaises(ValueError):self.run_tool([item])
 def test_unmapped_article_stays_explicit(self):
  item=self.extra();item['fields']['Instortvoorziening']='Unknown physical part';result,report=self.run_tool([self.main(),item]);self.assertEqual(result['articles'],2);self.assertFalse(result['completeness']['required_values_present']);self.assertIn('reviewed_article_catalogue_unresolved',result['issue_counts'])
 def test_create_requires_quantity_and_typed_source(self):
  for change in [dict(create='yes'),dict(source=dict(path=str(self.fixture.pdf),page=True)),dict(fields={'Instortvoorziening':'Fixture anchor M20'})]:
   with self.subTest(change=change),self.assertRaises(ValueError):self.run_tool([dict(self.extra(),**change)])
 def test_added_article_stays_under_its_element(self):
  import mbs_artikelcatalogus as reader
  _,report=self.fixture.run_tool();records=copy.deepcopy(report['records']);second=copy.deepcopy(records[0]);second['source']['merk']='TR02';second['fields']['Merk']='TR02';records.append(second)
  tool.apply_reviewed(records,[],[self.extra()],[],tool.audit.catalog(self.fixture.cat,'soest',reader),report['pages'])
  self.assertEqual([(r['kind'],r['source']['merk']) for r in records],[('element','TR01'),('article','TR01'),('article','TR01'),('element','TR02')])
  out=self.fixture.root/'grouped.xlsx';tool.build_xlsx(self.fixture.template,out,records);rows=list(reader.xlsx_bladen(out))[0][1]
  self.assertEqual(rows[-1]['cells'][0],'TR02')
 def test_piece_quantity_validation_ignores_json_field_order(self):
  item=self.extra();item['fields']={'Aantal':'0.5','Instortvoorziening':'Fixture anchor M20'}
  with self.assertRaisesRegex(ValueError,'integers'):self.run_tool([item])
 def test_malformed_review_rejected(self):
  with self.assertRaises(ValueError):self.run_tool([None])
 def test_no_accessories_requires_explicit_review(self):
  records=[dict(kind='element',source={'merk':'T1'},fields={'Aantal':'1','Lengte':'1','Breedte':'1','Dikte':'1','Mengsel':'Fixture','Hoofdregel':'10','Instortvoorziening':'Fixture','Netto m3':'1'})]
  self.assertFalse(tool.completeness(records)['required_values_present']);records[0]['articles_reviewed']={'complete':True,'evidence':'Fixture has no embedded parts'};self.assertTrue(tool.completeness(records)['required_values_present'])
if __name__=='__main__':unittest.main()
