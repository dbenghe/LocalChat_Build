"""Native-vector profile association must fail closed on missing/competing evidence."""
import sys,ast,argparse,unittest,types
from pathlib import Path
from unittest.mock import patch as mock
parser=argparse.ArgumentParser();parser.add_argument('--package',default=str(Path(__file__).resolve().parents[1]/'pdfcontrol'));args,rest=parser.parse_known_args();sys.argv=[sys.argv[0]]+rest;sys.path[:0]=[args.package,str(Path(__file__).parent)]
import pymupdf as fitz,mbs_controles_extra as ex
import profile_projection as pp
from apply_profile_projection import patch
fixed=patch(Path(ex.__file__).read_bytes().decode('utf8'))
for n in ast.parse(fixed).body:
 if isinstance(n,ast.FunctionDef) and (n.name.startswith('_profile_') or n.name=='controle_maatketens'):exec(compile(ast.Module(body=[n],type_ignores=[]),'profile-candidate','exec'),ex.__dict__)

class Projections(unittest.TestCase):
 def fixture(self,top=True,ticks=True,height=True,wall=True,horizontal=True,depth=15,shift=0,offset=False,ordinates=True):
  d=fitz.open();self.addCleanup(d.close);p=d.new_page(width=800,height=800)
  p.draw_rect(fitz.Rect(200,200,500,500));p.draw_rect(fitz.Rect(230,200,430,231))
  if top:p.draw_rect(fitz.Rect(230+shift,90,430+shift,90+depth))
  def dim(h,c,a,b,text,at=None,endticks=True):
   p.draw_line((a,c) if h else (c,a),(b,c) if h else (c,b))
   if endticks:
    for q in (a,b):p.draw_line((q,c-3) if h else (c-3,q),(q,c+3) if h else (c+3,q))
   along=(a+b)/2 if at is None else at
   p.insert_text((along-8,c-2) if h else (c-2,along+8),text,fontsize=8,rotate=0 if h else 90)
  if height:dim(False,150,180 if offset else 200,231,'310',190 if offset else None,ticks)
  if offset and ticks:p.draw_line((147,200),(153,200))
  if wall:dim(False,130,200,500,'3000')
  if horizontal:
   dim(True,180,230,430,'2000')
   if ordinates:
    p.insert_text((238.6,178),'300',fontsize=8,rotate=90)
    p.insert_text((438.6,178),'2300',fontsize=8,rotate=90)
  rows=[dict(pos='RSP1',omschrijving='Sparing rechthoek 150*310',aantal='1',aantal_n=1,rect=fitz.Rect(20,650,350,665))]
  return p,rows
 def adapter(self,p,rows):return types.SimpleNamespace(lees_artikel_tabel=lambda _: (rows,fitz.Rect(10,630,600,700)),tekstwoorden=ex.tekstwoorden,_artikel_maten=ex._artikel_maten,_getal_op_lijn=ex._getal_op_lijn,_sparing_ordinaten=ex._sparing_ordinaten)
 def confirmed(self,p,rows):return pp.confirmed(self.adapter(p,rows),p,rows)
 def test_complete_projection_with_independent_wall_and_placement(self):
  p,r=self.fixture();found=self.confirmed(p,r);self.assertEqual(list(found),['RSP1']);self.assertAlmostEqual(found['RSP1'][0]['depth'],150)
 def test_missing_top_projection(self):
  p,r=self.fixture(top=False);self.assertFalse(self.confirmed(p,r))
 def test_shifted_top_projection(self):
  p,r=self.fixture(shift=20);self.assertFalse(self.confirmed(p,r))
 def test_wrong_projection_depth(self):
  p,r=self.fixture(depth=18);self.assertFalse(self.confirmed(p,r))
 def test_missing_profile_ticks(self):
  p,r=self.fixture(ticks=False);self.assertFalse(self.confirmed(p,r))
 def test_missing_profile_value(self):
  p,r=self.fixture(height=False);self.assertFalse(self.confirmed(p,r))
 def test_no_independent_wall_reference(self):
  p,r=self.fixture(wall=False);self.assertFalse(self.confirmed(p,r))
 def test_width_without_x_placement_does_not_approve(self):
  p,r=self.fixture(ordinates=False);self.assertFalse(self.confirmed(p,r))
 def test_no_horizontal_placement(self):
  p,r=self.fixture(horizontal=False);self.assertFalse(self.confirmed(p,r))
 def test_quantity_mismatch(self):
  p,r=self.fixture();r[0]['aantal_n']=2;self.assertFalse(self.confirmed(p,r))
 def test_identical_profile_codes_cannot_split_quantity(self):
  p,r=self.fixture();r.append(dict(r[0],pos='RSP2'));self.assertFalse(self.confirmed(p,r))
 def test_other_article_family_is_not_a_profile(self):
  p,r=self.fixture();r[0]['omschrijving']='Schroefhuls 150*310';self.assertFalse(self.confirmed(p,r))
 def test_three_dimensional_spec_is_not_reinterpreted(self):
  p,r=self.fixture();r[0]['omschrijving']='Sparing rechthoek 150*310*2000';self.assertFalse(self.confirmed(p,r))
 def test_repainted_contours_not_double_counted(self):
  p,r=self.fixture();p.draw_rect(fitz.Rect(230,200,430,231));p.draw_rect(fitz.Rect(230,90,430,105));self.assertTrue(self.confirmed(p,r))
 def test_hook_idempotence_and_line_endings(self):
  self.assertEqual(patch(fixed),fixed);ast.parse(fixed)
 def test_unknown_layout_guard_is_not_bypassed(self):
  p,r=self.fixture()
  with mock.object(ex,'lees_artikel_tabel',lambda _: (r,fitz.Rect(10,630,600,700))):
   findings,overview=ex.controle_maatketens(p,r,fitz.Rect(10,630,600,700),ex.tekstwoorden(p))
  self.assertEqual(overview[0]['verdict'],'N.V.T.');self.assertTrue(any(f.code=='LAYOUT_NIET_HERKEND' for f in findings))
 def test_displaced_leader_value_does_not_borrow_adjacent_cell(self):
  p,r=self.fixture(height=False)
  p.draw_line((150,175),(150,250))
  for y in (200,231,250):p.draw_line((147,y),(153,y))
  p.insert_text((148,195),'310',fontsize=8,rotate=90)
  p.insert_text((148,247),'190',fontsize=8,rotate=90)
  values=pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,False,200,231,230,430)
  self.assertEqual({n for n,_,_ in values},{310})
 def test_intervening_tick_blocks_displaced_value(self):
  p,r=self.fixture(height=False)
  p.draw_line((150,175),(150,250))
  for y in (195,200,231,250):p.draw_line((147,y),(153,y))
  p.insert_text((148,190),'310',fontsize=8,rotate=90)
  values=pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,False,200,231,230,430)
  self.assertFalse(values)
 def test_3000_never_aliases_3001_rd(self):
  p,r=self.fixture();p.insert_text((300,300),'3000',fontsize=10,color=(1,0,1));item=dict(r[0],pos='3001(rd)',omschrijving='Schroefhuls+flens 1036-G M10-60 Rondom');self.assertFalse(ex.zoek_labels(p,item,None,regels=[item]))

if __name__=='__main__':unittest.main(verbosity=2)
