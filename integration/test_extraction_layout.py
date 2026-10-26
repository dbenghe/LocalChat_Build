"""Synthetic ABWN/Tekla extraction regressions; no production data."""
import argparse,sys,tempfile,unittest,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--package',required=True);args=p.parse_args();sys.path.insert(0,str(Path(args.package).resolve()))
import pymupdf
import mbs_webapp as web
from mbs_localchat_layout import abwn_project,abwn_classify,abwn_bundle_hint
COVER="Tekla Structures\nVB-BA1\nRenvooi\nProjectnummer ABWN // werknummer MBS:\n98765 / 123456"
def sheet(mark='BA1',title='VORM BALKON',extra=''):
 return 'Tekla Structures\nH20\nSU2\nINSTORTVOORZIENINGEN\nPos\nOmschrijving\nAantal\n'+title+'\n'+mark+'\nDefinitief\nSTATUS:\n'+extra
class ExtractionLayout(unittest.TestCase):
 def test_title_mark_wins_over_insert_positions(self):
  for family,title in [('BA','BALKON'),('GA','GALERIJ'),('DP','DAKPLAAT'),('TR','TRAP'),('BD','BORDES')]:
   for role,word in [('V','VORM'),('W','WAPENING')]:
    with self.subTest(family=family,role=role):
     info=web.classificeer_stempel(sheet(family+'2',word+' '+title));self.assertEqual((info['eigen'],info['rol']),(family+'2',role))
 def test_ambiguous_missing_and_non_tekla_marks_rejected(self):
  for text in [sheet('BA1',extra='BA2'),sheet(''),sheet('H20'),sheet('BA1',extra='WAPENING BALKON')]:
   self.assertIsNone(web.classificeer_stempel(text))
  self.assertFalse(abwn_classify(sheet().replace('Tekla Structures','Different producer'))[0])
 def test_cover_reads_six_digit_mbs_not_bureau_number(self):
  self.assertEqual(abwn_project(COVER),'123456');self.assertEqual(web.project_van_blad(COVER),'123456_');self.assertIsNone(web.classificeer_stempel(COVER))
 def test_bundle_inherits_unique_project_and_preserves_source(self):
  with tempfile.TemporaryDirectory() as folder:
   root=Path(folder);source=root/'bureau-book.pdf'
   with pymupdf.open() as doc:
    for text in [COVER,sheet('BA1'),sheet('BA2')]:doc.new_page(width=850,height=650).insert_text((40,40),text,fontsize=10)
    doc.save(source)
   digest=hashlib.sha256(source.read_bytes()).hexdigest();plan=web.plan_wapening(folder,fase='bundel')
   outputs=[row for row in plan if row['soort']=='extract'];self.assertEqual({row['doel'] for row in outputs},{'123456_BA1_V.pdf','123456_BA2_V.pdf'});self.assertEqual({row['pagina'] for row in outputs},{1,2})
   for row in outputs:
    self.assertTrue(web.schrijf_pagina(str(source),row['pagina'],str(root/row['doel'])))
    with pymupdf.open(root/row['doel']) as doc:self.assertEqual(len(doc),1);self.assertIn(row['doel'].split('_')[1],doc[0].get_text())
   self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),digest);self.assertFalse(list(root.glob('*_W.pdf')))
 def test_conflicting_covers_do_not_assign_project(self):
  with pymupdf.open() as doc:
   for text in [COVER,COVER.replace('123456','654321'),sheet()]:doc.new_page().insert_text((40,40),text,fontsize=10)
   self.assertEqual(abwn_bundle_hint(doc[2],doc[2].get_text()),'')
 def test_own_project_remains_authoritative(self):
  with pymupdf.open() as doc:
   for text in [COVER,sheet(extra='Projectnummer: 654321')]:doc.new_page().insert_text((40,40),text,fontsize=10)
   self.assertEqual(abwn_bundle_hint(doc[1],doc[1].get_text()),'');self.assertEqual(web.project_van_blad(doc[1].get_text()),'654321_')
 def test_project_does_not_leak_between_documents(self):
  for expected in ['123456','654321']:
   with pymupdf.open() as doc:
    for text in [COVER.replace('123456',expected),sheet()]:doc.new_page().insert_text((40,40),text,fontsize=10)
    self.assertEqual(abwn_bundle_hint(doc[1],doc[1].get_text()),'LOCALCHAT_ABWN_COVER_PROJECT: '+expected)
 def test_owned_hook_replay_is_idempotent_and_atomic_on_unknown_base(self):
  import importlib.util,ast
  spec=importlib.util.spec_from_file_location('hooks',Path(__file__).with_name('apply_extraction_layout.py'));hooks=importlib.util.module_from_spec(spec);spec.loader.exec_module(hooks)
  old='def old(tekst, page):\n    # ABWN Tekla title families:\n    pass\n    # Tekla full bare BD/TR sheet marks\n    from mbs_localchat_layout import project as specials_project\n    if len(tekst.strip()) >= 40:\n        return tekst\n'
  with tempfile.TemporaryDirectory() as folder:
   path=Path(folder)/'mbs_webapp.py';path.write_text(old,encoding='utf-8');hooks.apply(folder);first=path.read_bytes();ast.parse(first)
   self.assertIn(b'abwn_bundle_hint',first);self.assertIn(b'abwn_classify',first);self.assertIn(b'abwn_project',first);hooks.apply(folder);self.assertEqual(first,path.read_bytes())
   path.write_text('unsupported source',encoding='utf-8')
   with self.assertRaises(ValueError):hooks.apply(folder)
   self.assertEqual(path.read_text(encoding='utf-8'),'unsupported source')

if __name__=='__main__':unittest.main(argv=[sys.argv[0]],verbosity=2)
