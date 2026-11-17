"""Companion references must prove current geometry and drawn dimensions, never suppress unknowns."""
import sys,json,tempfile,unittest,copy
from pathlib import Path
from types import SimpleNamespace
sys.path[:0]=[str(Path(__file__).resolve().parents[1]/'pdfcontrol'),str(Path(__file__).parent)]
import pymupdf as fitz,mbs_controles_extra as ex,ifc_reference as ref,profile_projection as projection

class Companion(unittest.TestCase):
 def fixture(self,dimension=True,native=True,extra=False,shift=0,rotate=False,name='Stekanker M16x415 (Ø12) EV',unsupported=False,no_wall=False,length=415,points_only=False):
  temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);root=Path(temp.name);parts=[]
  def emit(kind,args):parts.append('#'+str(len(parts)+1)+'= '+kind+'('+args+');');return '#'+str(len(parts))
  unit=emit('IFCSIUNIT','*,.LENGTHUNIT.,.MILLI.,.METRE.');assignment=emit('IFCUNITASSIGNMENT','('+unit+')');emit('IFCPROJECT',"'project',$,'999001',$,$,$,$,$,"+assignment)
  origin=emit('IFCCARTESIANPOINT','(10000.,20000.,5000.)' if rotate else '(0.,0.,0.)');axisx=emit('IFCDIRECTION','(0.,1.,0.)' if rotate else '(1.,0.,0.)');axisz=emit('IFCDIRECTION','(0.,0.,1.)');axis=emit('IFCAXIS2PLACEMENT3D',','.join([origin,axisz,axisx]));base=emit('IFCLOCALPLACEMENT','$,'+axis)
  localorigin=emit('IFCCARTESIANPOINT','(0.,0.,0.)');localaxisx=emit('IFCDIRECTION','(1.,0.,0.)');localaxis=emit('IFCAXIS2PLACEMENT3D',','.join([localorigin,axisz,localaxisx]));placement=emit('IFCLOCALPLACEMENT',base+','+localaxis)
  def box(lo,hi):
   points=[emit('IFCCARTESIANPOINT','('+','.join(str(float(v)) for v in p)+')') for p in [(lo[0],lo[1],lo[2]),(hi[0],lo[1],lo[2]),(hi[0],hi[1],lo[2]),(lo[0],hi[1],lo[2]),(lo[0],lo[1],hi[2]),(hi[0],lo[1],hi[2]),(hi[0],hi[1],hi[2]),(lo[0],hi[1],hi[2])]]
   faces=[]
   for face in [(0,1,2,3),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]:
    poly=emit('IFCPOLYLOOP','('+','.join(points[i] for i in face)+')');bound=emit('IFCFACEOUTERBOUND',poly+',.T.');faces.append(emit('IFCFACE','('+bound+')'))
   shell=emit('IFCCLOSEDSHELL','('+','.join(faces)+')');body=emit('IFCFACETEDBREP',shell);rep=emit('IFCSHAPEREPRESENTATION',"$,'Body','Brep',("+body+')');return emit('IFCPRODUCTDEFINITIONSHAPE','$,$,('+rep+')')
  if not no_wall:emit('IFCWALL',"'wall',$,'Gevel','2000x3000',$,"+placement+','+box((0,0,0),(2000,150,3000))+",'wall-id'")
  for center in ([500+shift,1000] if extra else [500+shift]):
   representation=box((center-10.5,64.5,2970-length),(center+10.5,85.5,2970))
   if unsupported:representation=emit('IFCUNSUPPORTED','()')
   emit('IFCBUILDINGELEMENTPROXY',"'part',$,'"+name+"','PSA',$,"+placement+','+representation+",'part-id',.ELEMENT.")
  model=root/'999001-reference.ifc';model.write_text("ISO-10303-21;HEADER;FILE_SCHEMA(('IFC2X3'));ENDSEC;DATA;\n"+'\n'.join(parts)+'\nENDSEC;END-ISO-10303-21;',encoding='utf8')
  document=fitz.open();self.addCleanup(document.close);page=document.new_page(width=700,height=700);page.draw_rect(fitz.Rect(200,200,400,500));page.insert_text((20,620),'Projectnummer: 999001',fontsize=8);page.insert_text((20,632),'Rev A',fontsize=8)
  page.draw_line((130,200),(130,500))
  for y in (200,500):page.draw_line((127,y),(133,y))
  page.insert_text((128,358),'3000',fontsize=8,rotate=90)
  if native:
   rect=fitz.Rect(248.95,203,251.05,203+length*.1)
   if points_only:
    for corner in (rect.tl,rect.tr,rect.bl,rect.br):page.draw_line(corner,fitz.Point(corner.x+.01,corner.y))
   else:page.draw_rect(rect)
  if dimension:
   page.draw_line((200,550),(400,550))
   for x,t in [(200,'0'),(250,'500')]:page.draw_line((x,547),(x,553));page.insert_text((x+8.6,548),t,fontsize=8,rotate=90)
  pdf=root/'999001-V.pdf';document.save(pdf)
  registry=root/'reference_models.json';config={'schema':1,'projects':[{'project':'999001','ifc':{'path':str(model),'sha256':ref.sha(model)},'drawings':[{'path':str(pdf),'sha256':ref.sha(pdf),'page':1}]}]};registry.write_text(json.dumps(config),encoding='utf8')
  rows=[dict(pos='A001',omschrijving=name+' Malzijde',aantal='1',aantal_n=1,rect=fitz.Rect(20,650,350,665))]
  return SimpleNamespace(page=page,document=document,root=root,model=model,pdf=pdf,registry=registry,config=config,rows=rows)
 def engine(self):return SimpleNamespace(geometry=projection.geometry,rectangles=projection.rectangles,wall_scale=projection.wall_scale,words=ex.tekstwoorden,joined=projection.joined,ordinates=projection.ordinates,one_axis=ex.maar_een_maat_nodig,adapter=ex)
 def result(self,v):return ref.evaluate(v.page,v.rows,fitz.Rect(10,640,600,690),self.engine(),v.registry)
 def rewrite(self,v,old,new):
  v.model.write_text(v.model.read_text(encoding='utf8').replace(old,new),encoding='utf8');v.config['projects'][0]['ifc']['sha256']=ref.sha(v.model);v.registry.write_text(json.dumps(v.config),encoding='utf8')
 def test_complete_named_geometry_and_drawn_axis(self):
  v=self.fixture();result=self.result(v);self.assertTrue(result['A001']['approved']);self.assertTrue(result['A001']['parts'][0]['x_proof'])
 def test_no_drawn_axis_preserves_identification_without_approval(self):
  v=self.fixture(dimension=False);result=self.result(v);self.assertFalse(result['A001']['approved']);self.assertIn('vervangen geen',result['A001']['note'])
 def test_coincident_vertices_without_contour_are_insufficient(self):self.assertFalse(self.result(self.fixture(points_only=True)))
 def test_declared_anchor_length_must_match_reference_body(self):self.assertFalse(self.result(self.fixture(length=300)))
 def test_model_coordinates_do_not_replace_absent_native_symbol(self):self.assertFalse(self.result(self.fixture(native=False)))
 def test_geometry_change_invalidates_drawing_scope(self):
  v=self.fixture();v.page.draw_line((300,300),(310,310));self.assertFalse(self.result(v))
 def test_non_technical_review_date_is_not_a_revision(self):
  v=self.fixture();v.page.draw_rect(fitz.Rect(20,20,140,50),fill=(1,1,0),color=None);v.page.insert_text((25,40),'17-11-2026',fontsize=10);self.assertTrue(self.result(v)['A001']['approved'])
 def test_trusted_source_capture_precedes_checker_legend(self):
  v=self.fixture();ref.capture_source(v.page);v.page.draw_rect(fitz.Rect(150,40,270,80));v.page.insert_text((155,55),'X = maat horizontaal',fontsize=8);self.assertTrue(self.result(v)['A001']['approved'])
 def test_title_revision_change_is_not_ignored(self):
  v=self.fixture();v.page.insert_text((100,632),'Rev B',fontsize=8);self.assertFalse(self.result(v))
 def test_wrong_project_is_not_consulted(self):
  v=self.fixture();v.config['projects'][0]['project']='999002';v.registry.write_text(json.dumps(v.config));self.assertFalse(self.result(v))
 def test_ifc_project_filename_mismatch_rejected(self):
  v=self.fixture();other=v.root/'888001-reference.ifc';other.write_bytes(v.model.read_bytes());v.config['projects'][0]['ifc']['path']=str(other);v.registry.write_text(json.dumps(v.config));self.assertRaises(ref.Rejected,self.result,v)
 def test_changed_model_rejected_even_after_cached_success(self):
  v=self.fixture();self.assertTrue(self.result(v));v.model.write_text(v.model.read_text(encoding='utf8')+'\n');self.assertRaises(ref.Rejected,self.result,v)
 def test_changed_reference_drawing_is_not_cached_into_approval(self):
  v=self.fixture();self.assertTrue(self.result(v));v.pdf.write_bytes(v.pdf.read_bytes()+b'\n');self.assertFalse(self.result(v))
 def test_material_variant_is_not_aliased(self):
  v=self.fixture();v.rows[0]['omschrijving']=v.rows[0]['omschrijving'].replace(' EV ',' TV ');self.assertFalse(self.result(v))
 def test_table_quantity_mismatch(self):
  v=self.fixture();v.rows[0]['aantal_n']=2;self.assertFalse(self.result(v))
 def test_competing_table_owners(self):
  v=self.fixture();v.rows.append(dict(v.rows[0],pos='A002'));self.assertFalse(self.result(v))
 def test_extra_unmatched_reference_occurrence_cannot_be_dropped(self):self.assertFalse(self.result(self.fixture(extra=True)))
 def test_shifted_model_component_does_not_match_other_ink(self):self.assertFalse(self.result(self.fixture(shift=50)))
 def test_nested_translated_and_rotated_placements(self):self.assertTrue(self.result(self.fixture(rotate=True))['A001']['approved'])
 def test_missing_wall_cannot_establish_projection(self):self.assertFalse(self.result(self.fixture(no_wall=True)))
 def test_unsupported_component_cannot_be_ignored(self):self.assertFalse(self.result(self.fixture(unsupported=True)))
 def test_metres_are_not_guessed_as_millimetres(self):
  v=self.fixture();self.rewrite(v,'.MILLI.','$');self.assertRaises(ref.Rejected,self.result,v)
 def test_unassigned_unit_is_not_sufficient(self):
  v=self.fixture();self.rewrite(v,"'project',$,'999001',$,$,$,$,$,#2", "'project',$,'999001',$,$,$,$,$,$");self.assertRaises((ref.Rejected,KeyError),self.result,v)
 def test_unsupported_schema_is_not_guessed(self):
  v=self.fixture();self.rewrite(v,'IFC2X3','IFC4');self.assertRaises(ref.Rejected,self.result,v)
 def test_duplicate_entities_fail_closed(self):
  v=self.fixture();self.rewrite(v,'ENDSEC;END-ISO-10303-21;', '#1= IFCSIUNIT(*,.LENGTHUNIT.,.MILLI.,.METRE.);ENDSEC;END-ISO-10303-21;');self.assertRaises(ref.Rejected,self.result,v)
 def test_bent_anchor_keeps_its_additional_check(self):
  v=self.fixture(name='Stekanker M16x415 (Ø12) EV (buigen)');self.assertFalse(self.result(v)['A001']['approved'])
 def test_absent_registry_is_inert(self):
  v=self.fixture();v.registry.unlink();self.assertFalse(self.result(v))
 def test_parser_preserves_quoted_semicolon_and_reference_types(self):
  self.assertEqual(list(ref.records("#1= IFCX('a;it''s',#2);#2= IFCY(2.);")),["#1= IFCX('a;it''s',#2)","#2= IFCY(2.)"]);values=ref.parse("('a;it''s',#2,2.)");self.assertIsInstance(values[1],ref.Ref);self.assertNotIsInstance(values[2],ref.Ref)
 def test_parser_rejects_unrecognized_tokens(self):self.assertRaises(ref.Rejected,ref.parse,'(1.,@)')
 def test_unicode_diameter_encodings_keep_identity(self):
  self.assertEqual(ref.identity('Stekanker M16x415 (\\S\\X12) EV'),ref.identity('Stekanker M16x415 (Ø12) EV'));self.assertEqual(ref.decode('\\X2\\00D8\\X0\\'),'Ø')

 def test_shared_hook_preserves_unrelated_engine_changes_and_is_idempotent(self):
  from apply_ifc_reference import patch,patch_checker
  baseline="def foreign():\n    return 'unchanged'\n\ndef controle_maatketens(page, regels, tabelgebied):\n    profile_review = profile_result['review']\n    for profile_status in status:\n        profile_pos = profile_status['regel'].get('pos')\n        profile_status['geometrisch_herkend'] = (profile_pos in profile_proof)\n    for s in status:\n        if not s['labels']:\n            profiles = profile_proof.get(s['regel'].get('pos'))\n    return []\n"
  fixed=patch(baseline);self.assertEqual(patch(fixed),fixed);self.assertIn("return 'unchanged'",fixed);self.assertIn('reference_result',fixed)
  crlf=patch(baseline.replace('\n','\r\n'));self.assertNotIn('\n',crlf.replace('\r\n',''))
  checker='HEADINGS = {\n    "ARTIKEL_NIET_OP_TEKENING": "NIET AUTOMATISCH HERKEND",\n    "REAL_ERROR": "REAL ERROR",\n}\n\ndef check_pdf(doc):\n    page = doc[0]\n    return page\n'
  upgraded=patch_checker(checker);self.assertEqual(patch_checker(upgraded),upgraded);self.assertIn('"REAL_ERROR": "REAL ERROR"',upgraded)
 def test_rejected_reference_cannot_become_approval(self):
  from apply_ifc_reference import HELPER
  import types
  from unittest.mock import patch
  messages=[];namespace={'stil':SimpleNamespace(meld=lambda *args:messages.append(args))};exec(compile(HELPER,'reference-wrapper','exec'),namespace)
  module=types.ModuleType('mbs_ifc_reference')
  def reject(*args):raise ref.Rejected('stale source')
  module.evaluate=reject
  with patch.dict(sys.modules,{'mbs_ifc_reference':module}):self.assertEqual(namespace['_reference_component_evidence'](None,[],None,None),{})
  self.assertTrue(messages)

class AxisDisplayTests(unittest.TestCase):
 def test_missing_false_and_inapplicable_none_are_distinct(self):
  from apply_ifc_reference import patch_checker
  source='HEADINGS = {\n    "ARTIKEL_NIET_OP_TEKENING": "NIET AUTOMATISCH HERKEND",\n}\ndef check_pdf(doc):\n    page = doc[0]\n    return page\ndef axis(r):\n    return "OK" if r.y_dim else "ONTBREEKT"\n'
  fixed=patch_checker(source);self.assertEqual(patch_checker(fixed),fixed)
  namespace={};exec(fixed,namespace)
  for value,expected in [(True,'OK'),(False,'ONTBREEKT'),(None,'-')]:self.assertEqual(namespace['axis'](SimpleNamespace(y_dim=value)),expected)

if __name__=='__main__':unittest.main(verbosity=2)
