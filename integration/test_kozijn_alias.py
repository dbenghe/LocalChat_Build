"""Synthetic window-mark recognition, ambiguity and missing-dimension regressions."""
import ast,sys,unittest
from pathlib import Path
from unittest.mock import patch as mock_patch
sys.path[:0]=[str(Path(__file__).parent),str(Path(__file__).parents[1]/'pdfcontrol')]
import pymupdf as fitz
import mbs_controles_extra as ex
from apply_kozijn_alias import patch,MARKER
source=Path(ex.__file__).read_bytes().decode('utf-8')
node=next(n for n in ast.parse(patch(source)).body if isinstance(n,ast.FunctionDef) and n.name=='zoek_labels')
exec(compile(ast.Module(body=[node],type_ignores=[]),ex.__file__,'exec'),ex.__dict__)

def row(description='Kozijnmerk= S47 630*970',pos='P777'):
 return dict(pos=pos,omschrijving=description,aantal='1',aantal_n=1,rect=fitz.Rect(20,650,300,680))

class WindowMark(unittest.TestCase):
 def page(self,text='S47',rotate=0):
  d=fitz.open();self.addCleanup(d.close);p=d.new_page(width=700,height=700);p.insert_text((300,250),text,fontsize=10,rotate=rotate);return p
 def labels(self,p,r=None,rows=None,table=None):
  r=r or row();return ex.zoek_labels(p,r,table,regels=rows if rows is not None else [r])
 def test_explicit_code_found_without_repeating_kozijnmerk_word(self):
  self.assertTrue(self.labels(self.page()))
 def test_case_spacing_and_underscore_variants(self):
  for code,desc in [('s47','Kozijnmerk = S47 630*970'),('A-24','kozijnmerk= A-24 630*970'),('S_24','Kozijnmerk= S_24 630*970')]:
   with self.subTest(code=code):self.assertTrue(self.labels(self.page(code),row(desc)))
 def test_horizontal_and_vertical_labels(self):
  for rotation in (0,90,180,270):
   with self.subTest(rotation=rotation):self.assertTrue(self.labels(self.page(rotate=rotation)))
 def test_short_code_is_not_part_of_another_word(self):
  for text in ('S470','XS47','S47A','S047'):
   with self.subTest(text=text):self.assertFalse(self.labels(self.page(text)))
 def test_table_only_does_not_prove_drawing_presence(self):
  self.assertFalse(self.labels(self.page(),table=fitz.Rect(250,200,450,280)))
 def test_revision_filter_also_applies_to_window_alias(self):
  with mock_patch.object(ex,'_in_revisieregel',return_value=True) as revision:
   self.assertFalse(self.labels(self.page()));self.assertTrue(revision.called)
 def test_description_suffix_alone_is_not_a_window_alias(self):
  self.assertFalse(self.labels(self.page(),row('Arbitrary part S47 630*970')))
 def test_shared_alias_is_not_assigned_to_two_rows(self):
  r=row();other=row('Kozijnmerk= S47 600*900','P778');p=self.page();self.assertFalse(self.labels(p,r,[r,other]));self.assertFalse(self.labels(p,other,[r,other]))
 def test_another_mark_with_same_prefix_does_not_hide_exact_mark(self):
  r=row();other=row('Kozijnmerk= S470 600*900','P778');self.assertTrue(self.labels(self.page(),r,[r,other]))
 def test_wrong_dimensions_in_caption_are_not_approved(self):
  self.assertFalse(self.labels(self.page('S47 600*900')))
 def test_conflicting_multiline_size_is_rejected(self):
  p=self.page();p.insert_text((283,261),'600*900',fontsize=10);self.assertFalse(self.labels(p))
 def test_matching_multiline_size_is_recognized(self):
  p=self.page();p.insert_text((283,261),'630*970',fontsize=10);self.assertTrue(self.labels(p))
 def test_caption_without_position_dimensions_is_not_ok(self):
  p=self.page();r=row();_,overview=ex.controle_maatketens(p,[r],None,ex.tekstwoorden(p));self.assertNotEqual(overview[0]['verdict'],'OK')
 def test_one_position_axis_is_not_a_two_axis_approval(self):
  p=self.page();p.draw_line((100,252),(290,252))
  for x,text in ((120,'100'),(210,'630')):p.insert_text((x,250),text,fontsize=10)
  r=row();_,overview=ex.controle_maatketens(p,[r],None,ex.tekstwoorden(p));self.assertNotEqual(overview[0]['verdict'],'OK');self.assertTrue(overview[0]['x_ok']);self.assertFalse(overview[0]['tweede_as'])
 def test_patch_is_idempotent_and_preserves_other_functions(self):
  first=patch(source);self.assertEqual(first,patch(first));before=ast.parse(source);after=ast.parse(first)
  for a,b in zip(before.body,after.body):
   if isinstance(a,ast.FunctionDef) and a.name=='zoek_labels':continue
   self.assertEqual(ast.dump(a),ast.dump(b))
 def test_crlf_preserved(self):
  text=source.replace('\r\n','\n').replace('\n','\r\n');fixed=patch(text);self.assertNotIn('\n',fixed.replace('\r\n',''));self.assertEqual(fixed,patch(fixed))
 def test_unsupported_source_rejected(self):
  with self.assertRaises(ValueError):patch('def zoek_labels():\n    return []\n')

if __name__=='__main__':unittest.main(verbosity=2)
