"""Native-vector profile association must fail closed on missing/competing evidence."""
import sys,ast,argparse,unittest,types
from pathlib import Path
from unittest.mock import patch as mock
parser=argparse.ArgumentParser();parser.add_argument('--package',default=str(Path(__file__).resolve().parents[1]/'pdfcontrol'));args,rest=parser.parse_known_args();sys.argv=[sys.argv[0]]+rest;sys.path[:0]=[args.package,str(Path(__file__).parent)]
import pymupdf as fitz,mbs_controles_extra as ex
import profile_projection as pp
from apply_profile_projection import patch,patch_checker
fixed=patch(Path(ex.__file__).read_bytes().decode('utf8'))
for n in ast.parse(fixed).body:
 if isinstance(n,ast.FunctionDef) and (n.name.startswith('_profile_') or n.name in ('controle_maatketens','_niet_gevonden_uitleg')):exec(compile(ast.Module(body=[n],type_ignores=[]),'profile-candidate','exec'),ex.__dict__)

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
 def test_new_report_headings_preserve_all_existing_codes(self):
  checker=Path(args.package)/'mbs_pdf_checker_pdfcontrol.py';before=checker.read_bytes().decode('utf8');after=patch_checker(before)
  self.assertEqual(patch_checker(after),after);self.assertIn('PROFIEL_PLAATSMAAT_ONBEVESTIGD',after);self.assertIn('PROFIEL_POSITIECODE_ONBEVESTIGD',after);ast.parse(after)
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

 def cell_chain(self,p,labels=True):
  p.draw_line((200,160),(500,160))
  for x in (200,230,430,500):p.draw_line((x,157),(x,163))
  for x,text in ((207,'300'),(320,'2000'),(460,'700')):
   if labels or text=='2000':p.insert_text((x,158),text,fontsize=8)
 def test_complete_dimension_cells_anchor_x_without_ordinates(self):
  p,r=self.fixture(ordinates=False);self.cell_chain(p);self.assertTrue(self.confirmed(p,r))
 def test_internal_width_component_without_boundary_is_not_position(self):
  p,r=self.fixture(ordinates=False);self.cell_chain(p,labels=False);self.assertFalse(self.confirmed(p,r))
 def test_conflicting_cell_values_do_not_choose_expected(self):
  p,r=self.fixture(ordinates=False);self.cell_chain(p);p.insert_text((210,151),'250',fontsize=8);p.insert_text((460,151),'600',fontsize=8);self.assertFalse(self.confirmed(p,r))
 def test_scalar_cannot_borrow_a_value_from_a_subcell(self):
  p,r=self.fixture();self.cell_chain(p)
  values=pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,True,200,500,160,160)
  self.assertFalse([n for n,_,c in values if c==160])
 def test_staggered_number_within_font_band_is_read(self):
  p,r=self.fixture();p.draw_line((550,160),(650,160))
  for x in (550,650):p.draw_line((x,157),(x,163))
  p.insert_text((580,149),'1000',fontsize=9.5)
  self.assertTrue(any(n==1000 and c==160 for n,_,c in pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,True,550,650,160,160)))
 def test_remote_staggered_text_is_not_borrowed(self):
  p,r=self.fixture();p.draw_line((550,160),(650,160))
  for x in (550,650):p.draw_line((x,157),(x,163))
  p.insert_text((580,130),'1000',fontsize=9.5)
  self.assertFalse([n for n,_,c in pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,True,550,650,160,160) if c==160])
 def test_ordinate_calibration_uses_actual_reference_value(self):
  p,r=self.fixture(wall=False)
  p.draw_line((130,200),(130,500))
  for y,text in ((200,'3000'),(500,'0')):
   p.draw_line((127,y),(133,y));width=fitz.get_text_length(text,fontsize=8);p.insert_text((125-width,y-2.4),text,fontsize=8)
  g=pp.geometry(p);scale=pp.wall_scale(self.adapter(p,r),p,g,ex.tekstwoorden(p),None,(200,200,500,500))
  self.assertAlmostEqual(scale,10);self.assertTrue(self.confirmed(p,r))
 def test_reading_on_other_side_of_rotated_text_is_not_borrowed(self):
  p,r=self.fixture(height=False);p.draw_line((150,200),(150,231))
  for y in (200,231):p.draw_line((147,y),(153,y))
  p.insert_text((160,225),'310',fontsize=8,rotate=90)
  self.assertFalse([n for n,_,c in pp.readings(self.adapter(p,r),p,pp.geometry(p),ex.tekstwoorden(p),None,False,200,231,150,150) if c==150])
 def records(self,pos='RSP1',count=1,horizontal=True):
  return [dict(pos=pos,profile=(150,310),box=(200+index*50,200,240+index*50,231),top=(200+index*50,90,240+index*50,105),wall_reference=True,horizontal_proven=horizontal,x_edges=(False,True),height=310,depth=150,scale=10,length=400) for index in range(count)]
 def test_recognized_profile_with_missing_placement_remains_warning(self):
  p,r=self.fixture();matches=self.records(horizontal=False)
  with mock.object(pp,'analyse',return_value=(matches,0)):result=pp.evaluate(self.adapter(p,r),p,r)
  self.assertFalse(result['approved']);self.assertEqual(result['review']['RSP1']['code'],'PROFIEL_PLAATSMAAT_ONBEVESTIGD');self.assertIn('linkerrand',result['review']['RSP1']['note'])
 def test_equal_profiles_are_reported_without_arbitrary_code_assignment(self):
  p,r=self.fixture();r[0]['aantal_n']=2;r.append(dict(r[0],pos='RSP2',aantal_n=1));matches=self.records(count=3)+self.records('RSP2',count=3)
  with mock.object(pp,'analyse',return_value=(matches,0)):result=pp.evaluate(self.adapter(p,r),p,r)
  self.assertFalse(result['approved']);self.assertEqual(set(result['review']),{'RSP1','RSP2'});self.assertTrue(all(v['code']=='PROFIEL_POSITIECODE_ONBEVESTIGD' for v in result['review'].values()))
 def test_partial_quantity_cannot_claim_complete_geometry(self):
  p,r=self.fixture();r[0]['aantal_n']=2
  with mock.object(pp,'analyse',return_value=(self.records(horizontal=False),0)):result=pp.evaluate(self.adapter(p,r),p,r)
  self.assertFalse(result['approved']);self.assertFalse(result['review'])

 def test_unrecognized_identity_does_not_claim_missing_axes(self):
  d=fitz.open();self.addCleanup(d.close);p=d.new_page(width=700,height=700)
  for a,b in [((200,200),(260,200)),((260,200),(260,290)),((260,290),(200,290)),((200,290),(200,200))]:p.draw_line(a,b)
  p.insert_text((212,240),'C31/S31',fontsize=8);p.insert_text((214,251),'600*900',fontsize=8)
  p.draw_line((180,380),(300,380))
  for x in (200,260):p.draw_line((x,375),(x,385))
  p.insert_text((218,378),'600',fontsize=8);p.draw_line((440,180),(440,320))
  for y in (200,290):p.draw_line((435,y),(445,y))
  p.insert_text((437,260),'900',fontsize=8,rotate=90)
  rows=[dict(pos='K001',omschrijving='Kozijnmerk= C31/S31 600*900',aantal='1',aantal_n=1,rect=fitz.Rect(20,650,350,660)),dict(pos='3302',omschrijving='Stekanker M16x415 EV Malzijde',aantal='3',aantal_n=3,rect=fitz.Rect(20,665,350,675))]
  findings,overview=ex.controle_maatketens(p,rows,fitz.Rect(10,640,600,690),ex.tekstwoorden(p));unknown=next(v for v in overview if v['pos']=='3302')
  self.assertEqual(unknown['verdict'],'CONTROLEREN');self.assertIsNone(unknown['x_ok']);self.assertIsNone(unknown['tweede_as']);self.assertTrue(any(v.code=='ARTIKEL_NIET_OP_TEKENING' for v in findings))
 def test_report_counts_include_independently_recognized_geometry(self):
  unknown={'labels':[],'kort':'unknown'};geometry={'labels':[],'kort':'profile','geometrisch_herkend':True};labelled={'labels':[fitz.Rect(1,1,2,2)],'kort':'named'}
  text=ex._niet_gevonden_uitleg([unknown,geometry,labelled],unknown,kort=True)
  self.assertIn('als enige van de 3',text);self.assertIn('andere 2',text)

if __name__=='__main__':unittest.main(verbosity=2)
