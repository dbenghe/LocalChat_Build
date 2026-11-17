"""Mixed-layout page coverage, information-page safety and title-block projects."""
import ast,sys,tempfile,unittest,argparse
from pathlib import Path
from unittest.mock import patch as mock
parser=argparse.ArgumentParser();parser.add_argument('--package',default=str(Path(__file__).resolve().parents[1]/'pdfcontrol'));args,remaining=parser.parse_known_args();sys.argv=[sys.argv[0]]+remaining
sys.path[:0]=[str(Path(args.package)),str(Path(__file__).parent)]
import pymupdf as fitz,mbs_controles_extra as ex,mbs_pdf_checker_pdfcontrol as checker
from apply_bundle_coverage import patch_extra,patch_checker
sources={m.__name__:Path(m.__file__).read_bytes().decode('utf8') for m in (ex,checker)}
for module,patch,names in ((ex,patch_extra,('is_projecttekening','projectnummer_van_blad')),(checker,patch_checker,('check_pdf_alle',))):
 for node in ast.parse(patch(sources[module.__name__])).body:
  if isinstance(node,ast.FunctionDef) and node.name in names:exec(compile(ast.Module(body=[node],type_ignores=[]),'candidate','exec'),module.__dict__)

class BundleCoverage(unittest.TestCase):
 def page(self):
  d=fitz.open();self.addCleanup(d.close);return d.new_page(width=800,height=800)
 def test_mixed_naming_never_removes_unknown_drawings(self):
  d=fitz.open();self.addCleanup(d.close);d.new_page().insert_text((40,40),'ABC_V')
  for text in ('WALL-42\nDimension 1200 800','01.4821\nBetonsterkte C35/45','Special custom layout without known suffix',''):
   self.assertTrue(ex.is_projecttekening('mixed.pdf',text,d)[0])
 def test_information_pages_are_explicit(self):
  for text in ('Voorblad\nProject 123456','TEKENINGENLIJST\nSheet index','General notes\nSpecification'):
   self.assertFalse(ex.is_projecttekening('mixed.pdf',text)[0])
 def test_mixed_technical_page_is_not_a_cover(self):
  self.assertTrue(ex.is_projecttekening('mixed.pdf','Voorblad\nInstortvoorzieningen\nPos Omschrijving Aantal\n123 Deel 2')[0])
 def test_filename_does_not_turn_cover_into_drawing(self):
  self.assertFalse(ex.is_projecttekening('123456_ABC_V.pdf','Voorblad\nProjectgegevens')[0])
 def test_reference_to_information_list_is_not_a_cover_title(self):
  self.assertTrue(ex.is_projecttekening('bundle.pdf','Zie tekeningenlijst voor meer gegevens\nA-42')[0])
 def test_generic_project_label_rotated_and_separate_from_foreign_number(self):
  for rotation in (0,90,180,270):
   p=self.page();p.insert_text((400,400),'PROJECTNR.:',fontsize=10,rotate=rotation)
   offset={0:(470,400),90:(400,330),180:(330,400),270:(400,470)}[rotation]
   p.insert_text(offset,'123456',fontsize=10,rotate=rotation);p.insert_text((60,60),'654321',fontsize=10)
   self.assertEqual(ex.projectnummer_van_blad(p)[0],'123456')
 def test_explicit_mbs_label_has_priority(self):
  p=self.page();p.insert_text((40,100),'Projectnummer MBS',fontsize=10);p.insert_text((50,120),'123456',fontsize=10);p.insert_text((500,600),'PROJECTNR.',fontsize=10);p.insert_text((510,620),'654321',fontsize=10)
  self.assertEqual(ex.projectnummer_van_blad(p)[0],'123456')
 def test_distant_or_conflicting_project_labels_are_not_guessed(self):
  p=self.page();p.insert_text((40,100),'PROJECTNR.',fontsize=10);p.insert_text((500,600),'123456',fontsize=10);p.insert_text((600,650),'654321',fontsize=10)
  self.assertEqual(ex.projectnummer_van_blad(p)[0],'')
 def test_near_ambiguous_values_are_not_guessed(self):
  p=self.page();p.insert_text((300,300),'PROJECTNR.',fontsize=10);p.insert_text((300,280),'123456',fontsize=10);p.insert_text((300,320),'654321',fontsize=10)
  self.assertEqual(ex.projectnummer_van_blad(p)[0],'')
 def bundle(self,texts):
  temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);path=Path(temp.name)/'mixed.pdf';d=fitz.open()
  for text in texts:d.new_page().insert_text((40,100),text,fontsize=10)
  d.save(path);d.close();return path,Path(temp.name)/'out'
 def run_fake(self,texts,same_name=False):
  path,out=self.bundle(texts);calls=[]
  def run(source,dest,paginanr=0,naam=None):
   calls.append((paginanr,naam));return(str(out/(str(naam)+'.pdf')),[],[],'OK','Test')
  with mock.object(checker,'check_pdf',side_effect=run):
   if same_name:
    with mock.object(checker,'bladnaam',return_value='SAME_V'):result=checker.check_pdf_alle(str(path),str(out))
   else:result=checker.check_pdf_alle(str(path),str(out))
  return calls,result
 def test_45_mixed_pages_covered_exactly_once(self):
  calls,result=self.run_fake([f'Instortvoorzieningen\nProject {123450+i%5}\nSheet custom-{i}' for i in range(45)])
  self.assertEqual([i for i,n in calls],list(range(45)));self.assertEqual(len(result),45)
 def test_duplicate_names_get_unique_page_identity(self):
  calls,result=self.run_fake(['drawing one','drawing two'],same_name=True)
  self.assertEqual([n for i,n in calls],['SAME_V_p001','SAME_V_p002']);self.assertEqual(len({r[0] for r in result}),2)
 def test_explicit_cover_only_is_skipped(self):
  calls,_=self.run_fake(['Voorblad','Instortvoorzieningen','Unfamiliar drawing']);self.assertEqual([i for i,n in calls],[1,2])
 def test_inventory_error_cannot_report_first_page_as_success(self):
  path,out=self.bundle(['one','two'])
  with mock.object(checker,'bladnaam',side_effect=RuntimeError('read failure')),mock.object(checker,'check_pdf') as run:
   with self.assertRaises(RuntimeError):checker.check_pdf_alle(str(path),str(out))
   run.assert_not_called()
 def test_page_control_failure_is_not_partial_success(self):
  path,out=self.bundle(['one','two'])
  with mock.object(checker,'check_pdf',side_effect=RuntimeError('page failure')):
   with self.assertRaises(RuntimeError):checker.check_pdf_alle(str(path),str(out))
 def test_paths_colliding_after_control_fail_the_bundle(self):
  path,out=self.bundle(['one','two'])
  with mock.object(checker,'check_pdf',return_value=(str(out/'same.pdf'),[],[],'OK','Test')):
   with self.assertRaises(RuntimeError):checker.check_pdf_alle(str(path),str(out))
 def test_idempotent_and_unrelated_functions_preserved(self):
  for module,patch,allowed in ((ex,patch_extra,{'is_projecttekening','projectnummer_van_blad'}),(checker,patch_checker,{'check_pdf_alle'})):
   source=sources[module.__name__];fixed=patch(source);self.assertEqual(fixed,patch(fixed))
   before={n.name:ast.dump(n) for n in ast.parse(source).body if isinstance(n,ast.FunctionDef)};after={n.name:ast.dump(n) for n in ast.parse(fixed).body if isinstance(n,ast.FunctionDef)}
   for name in before:
    if name not in allowed:self.assertEqual(before[name],after[name])
   crlf=source.replace('\r\n','\n').replace('\n','\r\n');self.assertNotIn('\n',patch(crlf).replace('\r\n',''))

if __name__=='__main__':unittest.main(verbosity=2)
