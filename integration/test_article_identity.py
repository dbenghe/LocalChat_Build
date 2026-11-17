"""Identity recognition must not approve absent dimensions or incompatible variants."""
import ast,sys,unittest,argparse
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--package',default=str(Path(__file__).resolve().parents[1]/'pdfcontrol'));args,rest=parser.parse_known_args();sys.argv=[sys.argv[0]]+rest;sys.path[:0]=[str(Path(args.package)),str(Path(__file__).parent)]
import pymupdf as fitz,mbs_controles_extra as ex
from apply_article_identity import patch,patch_checker
source=Path(ex.__file__).read_bytes().decode('utf8');fixed=patch(source)
changed={'is_pos_code','_kozijnmerk','zoek_labels','controle_maatketens','_article_identity_variants','_window_dimension_conflicts','_shape_description','_directe_sparingmaten'}
for n in ast.parse(fixed).body:
 if isinstance(n,ast.FunctionDef) and n.name in changed:exec(compile(ast.Module(body=[n],type_ignores=[]),'candidate','exec'),ex.__dict__)

def row(pos='3204(s)',desc='Schroefhuls T-FIXX verzinkt M12-70 Stortzijde',number=2):return dict(pos=pos,omschrijving=desc,aantal=str(number),aantal_n=number,rect=fitz.Rect(20,650,350,665))

class Identities(unittest.TestCase):
 def page(self,text='',colour=(0,0,0)):
  d=fitz.open();self.addCleanup(d.close);p=d.new_page(width=700,height=700)
  if text:p.insert_text((300,250),text,fontsize=10,color=colour)
  return p
 def labels(self,p,r,rows=None,table=None):return ex.zoek_labels(p,r,table,regels=rows or [r])
 def test_parenthesized_position_codes_are_preserved(self):
  for value in ('3204(s)','3204 (s)','3001(rd)','B24(sz)','421(mz)','3204(l)'):self.assertTrue(ex.is_pos_code(value),value)
 def test_technical_values_are_not_qualified_positions(self):
  for value in ('150(mm)','EV(s)','st(s)','3204(12)','description (s)'):self.assertFalse(ex.is_pos_code(value),value)
 def test_spacing_case_and_colour_of_exact_qualified_label(self):
  for text,colour in [('3204 (s)',(1,0,1)),('3204(S)',(0,1,1)),('3204 ( s )',(0,0,0))]:self.assertTrue(self.labels(self.page(text,colour),row()))
 def test_other_qualified_side_cannot_be_borrowed(self):
  for text in ('3204(l)','3204(rd)','13204(s)','32040(s)'):self.assertFalse(self.labels(self.page(text),row()),text)
 def test_table_text_is_not_drawing_presence(self):
  p=self.page('3204 (s)');self.assertFalse(self.labels(p,row(),table=fitz.Rect(280,220,380,260)))
 def test_same_colour_does_not_identify_another_item(self):
  p=self.page('3204 (s)',(1,0,1));r=row('3001(rd)','Schroefhuls+flens M10-60 Rondom');self.assertFalse(self.labels(p,r))
 def test_plain_coloured_150_is_not_placement_proof(self):
  p=self.page('3204 (s)',(1,0,1));p.insert_text((310,280),'150',fontsize=10,color=(1,0,1));r=row();_,o=ex.controle_maatketens(p,[r],None,ex.tekstwoorden(p));self.assertNotEqual(o[0]['verdict'],'OK')
 def test_native_table_keeps_qualified_pos_and_quantity(self):
  p=self.page()
  for point,text in [((20,550),'INSTORTVOORZIENINGEN'),((20,565),'Pos'),((100,565),'Omschrijving'),((400,565),'Aantal'),((20,580),'3204(s)'),((100,580),'Schroefhuls T-FIXX M12-70'),((400,580),'6'),((20,595),'3001(rd)'),((100,595),'Schroefhuls flens M10-60'),((400,595),'2')]:p.insert_text(point,text,fontsize=8,color=(1,0,1))
  rows,_=ex.lees_artikel_tabel(p);self.assertEqual({r['pos']:r['aantal_n'] for r in rows},{'3204(s)':6,'3001(rd)':2})
 def test_full_compound_window_mark(self):
  r=row('P777','Kozijnmerk= C31/S31SP 600*900');self.assertEqual(ex._kozijnmerk(r['omschrijving']),'C31/S31SP');self.assertTrue(self.labels(self.page('C31/S31SP'),r))
 def test_compound_window_does_not_match_a_prefix_or_fragment(self):
  r=row('P777','Kozijnmerk= C31/S31SP 600*900')
  for text in ('C31','S31SP','C31/S31SP0','XC31/S31SP'):self.assertFalse(self.labels(self.page(text),r),text)
 def test_duplicate_compound_mark_is_ambiguous(self):
  r=row('P777','Kozijnmerk= C31/S31SP 600*900');other=row('P778','Kozijnmerk= C31/S31SP 650*900');p=self.page('C31/S31SP');self.assertFalse(self.labels(p,r,[r,other]));self.assertFalse(ex._window_dimension_conflicts(p,r,None,[r,other]))
 def test_multiline_actual_size_conflict_is_reported(self):
  p=self.page('C31/S31SP');p.insert_text((295,262),'650*900',fontsize=10);r=row('P777','Kozijnmerk= C31/S31SP 600*900');found=ex._window_dimension_conflicts(p,r,None,[r]);self.assertTrue(found);self.assertIn('650',ex._shape_description(found[0][0]))
 def test_matching_multiline_size_is_not_a_conflict(self):
  p=self.page('C31/S31SP');p.insert_text((295,262),'600*900',fontsize=10);r=row('P777','Kozijnmerk= C31/S31SP 600*900');self.assertFalse(ex._window_dimension_conflicts(p,r,None,[r]))
 def test_table_or_revision_size_does_not_create_a_conflict(self):
  p=self.page('C31/S31SP');p.insert_text((295,262),'650*900',fontsize=10);r=row('P777','Kozijnmerk= C31/S31SP 600*900');self.assertFalse(ex._window_dimension_conflicts(p,r,fitz.Rect(280,220,380,270),[r]))
 def contour(self,vertical=True,horizontal=True,shifted=False):
  p=self.page();x0,x1,y0,y1=200,260,200,290
  for a,b in [((x0,y0),(x1,y0)),((x1,y0),(x1,y1)),((x1,y1),(x0,y1)),((x0,y1),(x0,y0))]:p.draw_line(a,b)
  p.insert_text((212,240),'C31/S31',fontsize=8);p.insert_text((214,251),'600*900',fontsize=8)
  if horizontal:
   shift=80 if shifted else 0;p.draw_line((180+shift,380),(300+shift,380))
   for x in (x0+shift,x1+shift):p.draw_line((x,375),(x,385))
   p.insert_text((218+shift,378),'600',fontsize=8)
  if vertical:
   p.draw_line((440,180),(440,320))
   for y in (y0,y1):p.draw_line((435,y),(445,y))
   p.insert_text((437,260),'900',fontsize=8,rotate=90)
  return p
 def direct(self,p):
  r=row('P777','Kozijnmerk= C31/S31 600*900');labels=self.labels(p,r);return ex._directe_sparingmaten(p,r,labels,ex.tekstwoorden(p),None)
 def test_window_uses_proven_contour_and_shared_height_dimension(self):self.assertEqual({h for h,v,r in self.direct(self.contour())},{True,False})
 def test_missing_window_axis_is_not_invented_from_its_size(self):self.assertEqual({h for h,v,r in self.direct(self.contour(vertical=False))},{True})
 def test_dimension_on_another_contour_is_not_borrowed(self):self.assertNotIn(True,{h for h,v,r in self.direct(self.contour(shifted=True))})
 def test_window_size_text_alone_is_not_position_dimensions(self):self.assertFalse(self.direct(self.contour(vertical=False,horizontal=False)))
 def test_placement_suffix_can_be_omitted_from_unique_caption(self):
  r=row('3003','RD 16 Malzijde');self.assertTrue(self.labels(self.page('RD 16'),r))
 def test_placement_omission_does_not_merge_two_table_variants(self):
  r=row('3003','RD 16 Malzijde');other=row('3004','RD 16 Stortzijde');self.assertFalse(self.labels(self.page('RD 16'),r,[r,other]))
 def test_material_or_type_suffix_is_preserved(self):
  r=row('3003','RD 16 Malzijde');self.assertFalse(self.labels(self.page('RD 16RVS'),r))
 def test_hs_decimal_load_notation_is_exact_not_fuzzy(self):
  r=row('Lift','Hijsanker HS 2,5-21');self.assertTrue(self.labels(self.page('HS2-5-21'),r));self.assertFalse(self.labels(self.page('HS2-5-210'),r));self.assertFalse(self.labels(self.page('HS5-0-41'),r))
 def test_hooks_idempotent_and_other_functions_unchanged(self):
  self.assertEqual(fixed,patch(fixed));before={n.name:ast.dump(n) for n in ast.parse(source).body if isinstance(n,ast.FunctionDef)};after={n.name:ast.dump(n) for n in ast.parse(fixed).body if isinstance(n,ast.FunctionDef)}
  for name in before:
   if name not in changed:self.assertEqual(before[name],after[name],name)
  crlf=source.replace('\r\n','\n').replace('\n','\r\n');self.assertNotIn('\n',patch(crlf).replace('\r\n',''))
 def test_unrecognized_heading_does_not_claim_absence(self):
  s='HEADINGS={\n    "ARTIKEL_NIET_OP_TEKENING": "NIET OP TEKENING",\n}\n';new=patch_checker(s);self.assertIn('NIET AUTOMATISCH HERKEND',new);self.assertIn('KOZIJNMAAT VERSCHILT',new);self.assertEqual(new,patch_checker(new))

if __name__=='__main__':unittest.main(verbosity=2)
