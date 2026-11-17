import ast,sys,unittest
from pathlib import Path
from unittest.mock import patch as mock
sys.path.insert(0,str(Path(__file__).resolve().parent));from apply_dimension_ink import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'pdfcontrol'))
import fitz,mbs_controles_extra as ex
source=Path(ex.__file__).read_bytes().decode('utf8');fixed=patch(source)
for node in ast.parse(fixed).body:
 if isinstance(node,ast.FunctionDef) and node.name in ('_dimension_ink_box','controle_overlap','kozijn_ouder'):
  exec(compile(ast.Module(body=[node],type_ignores=[]),ex.__file__,'exec'),ex.__dict__)

class DimensionInk(unittest.TestCase):
 def page(self):
  d=fitz.open();self.addCleanup(d.close);return d.new_page(width=600,height=600)
 def findings(self,p):return ex.controle_overlap(p,ex.tekstwoorden(p),None)
 def test_metric_boxes_can_overlap_without_unreadable_digits(self):
  p=self.page();p.insert_text((300,250),'100',fontsize=9.45,rotate=90);p.insert_text((293.7,250),'0',fontsize=9.45,rotate=90)
  self.assertFalse(self.findings(p))
 def test_real_overlaps_still_reported_all_rotations(self):
  for rotate in (0,90,180,270):
   p=self.page();p.insert_text((300,300),'100',fontsize=10,rotate=rotate);p.insert_text((300,300),'888',fontsize=10,rotate=rotate)
   self.assertTrue(self.findings(p),rotate)
 def test_identical_double_painted_text_is_not_a_collision(self):
  p=self.page()
  for _ in range(2):p.insert_text((200,200),'100',fontsize=10)
  self.assertFalse(self.findings(p))
 def test_table_excluded(self):
  p=self.page();p.insert_text((200,200),'100',fontsize=10);p.insert_text((200,200),'888',fontsize=10)
  self.assertFalse(ex.controle_overlap(p,ex.tekstwoorden(p),fitz.Rect(150,150,250,250)))
 def test_failure_falls_back_to_existing_metric_box(self):
  p=self.page();p.insert_text((200,200),'100',fontsize=10);w=ex.tekstwoorden(p)[0]
  with mock.object(type(p),'get_svg_image',side_effect=RuntimeError('unavailable')):
   self.assertEqual(ex._dimension_ink_box(p,w),w['rect'])
 def test_unsupported_svg_is_not_trusted(self):
  p=self.page();p.insert_text((200,200),'100',fontsize=10);w=ex.tekstwoorden(p)[0]
  with mock.object(type(p),'get_svg_image',return_value='<svg/>'):
   self.assertEqual(ex._dimension_ink_box(p,w),w['rect'])
 def test_window_parent_explicit_variants(self):
  child=dict(pos='3801',omschrijving='Kozijnanker')
  for desc in ('Kozijnmerk= S47 630*970','kozijnmerk = S47 630*970','Merk S47'):
   parent=dict(pos='P777',omschrijving=desc);self.assertIs(ex.kozijn_ouder(child,[child,parent]),parent)
 def test_non_window_is_not_a_parent(self):
  child=dict(pos='3801',omschrijving='Kozijnanker')
  for desc in ('Kozijnsparing zonder sponning','Reference Kozijnmerk= S47','Kozijnmerk= 123','Leuningplug'):
   self.assertIsNone(ex.kozijn_ouder(child,[child,dict(pos='K777',omschrijving=desc)]))
 def test_table_only_window_does_not_clear_anchor(self):
  p=self.page();rows=[dict(pos='3801',omschrijving='Kozijnanker',rect=fitz.Rect(10,500,200,510),aantal='16',aantal_n=16),dict(pos='P777',omschrijving='Kozijnmerk= S47 630*970',rect=fitz.Rect(10,520,200,530),aantal='1',aantal_n=1)]
  f,_=ex.controle_maatketens(p,rows,None,ex.tekstwoorden(p));self.assertFalse(any(x.code=='GEKOPPELD_ARTIKEL' and '3801' in x.artikel for x in f))
 def test_idempotent_and_only_target_functions_changed(self):
  self.assertEqual(fixed,patch(fixed));before={n.name:ast.dump(n) for n in ast.parse(source).body if isinstance(n,ast.FunctionDef)};after={n.name:ast.dump(n) for n in ast.parse(fixed).body if isinstance(n,ast.FunctionDef)}
  for name in before:
   if name not in ('kozijn_ouder','controle_overlap'):self.assertEqual(before[name],after[name],name)
 def test_crlf_preserved(self):
  text=source.replace('\r\n','\n').replace('\n','\r\n');self.assertNotIn('\n',patch(text).replace('\r\n',''))
 def test_unsupported_source_fails_closed(self):
  with self.assertRaises(KeyError):patch('def other():\n return None\n')

if __name__=='__main__':unittest.main(verbosity=2)
