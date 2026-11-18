"""Labelled identity and uncertain-role regressions; no drawings, models or external services."""
import argparse,sys,tempfile,unittest,importlib.util,ast
from pathlib import Path
import pymupdf as p
ap=argparse.ArgumentParser();ap.add_argument('--package');args=ap.parse_args();sys.path[:0]=[str(Path(__file__).parent),str(Path(args.package))]
import stamp_identity as s,apply_stamp_identity as hooks,mbs_webapp as web,mbs_controles_extra as extra
class Stamps(unittest.TestCase):
 def page(self,role='',identity='01.1234',rotation=0,plot='G9999',label=True):
  d=p.open();self.addCleanup(d.close);page=d.new_page(width=800,height=600)
  if label:page.insert_text((430,500),'ELEMENTNR.:',fontsize=8)
  page.insert_text((510,500),identity,fontsize=8)
  page.insert_text((30,580),plot,fontsize=8)
  page.insert_text((30,30),'INSTORTVOORZIENINGEN\nPos Omschrijving Aantal\nConcrete drawing with dimensions and articles',fontsize=8)
  if role:page.insert_text((30,80),role,fontsize=8)
  page.set_rotation(rotation);return page
 def test_unknown_role_never_becomes_footer_reinforcement(self):
  page=self.page();self.assertEqual(s.reading(page)['eigen'],'01.1234');self.assertEqual(s.reading(page)['rol'],'?');self.assertEqual(s.classify(s.hint(page)),(True,None))
  self.assertIsNone(web.classificeer_stempel(web._pagina_tekst_voor_classificatie(page)))
 def test_positive_v_and_w(self):
  for heading,role in [('Vormtekening','V'),('Betonvorm','V'),('Wapeningstekening','W'),('STAVEN LOS BIJ LEGGEN','W'),('WAPENINGSMATTEN','W')]:
   page=self.page(heading);read=s.reading(page);self.assertEqual(read['rol'],role);self.assertEqual(web.classificeer_stempel(web._pagina_tekst_voor_classificatie(page))['eigen'],'01.1234')
 def test_page_rotations(self):
  for rotation in [0,90,180,270]:self.assertEqual(s.reading(self.page('Vormtekening',rotation=rotation))['eigen'],'01.1234')
 def test_content_rotations(self):
  for angle in [90,180,270]:
   page=self.page('Vormtekening',rotation=angle);page.remove_rotation();self.assertEqual(s.reading(page)['eigen'],'01.1234')
 def test_other_title_format_unchanged(self):self.assertIsNone(s.reading(self.page('Vormtekening',identity='GP123')))
 def test_missing_label_unhandled(self):self.assertIsNone(s.reading(self.page(label=False)))
 def test_foreign_body_ids_not_borrowed(self):
  page=self.page(identity='GP123');page.insert_text((100,200),'01.9876');self.assertIsNone(s.reading(page))
 def test_equal_glyphs_not_multiple_identities(self):
  page=self.page('Vormtekening');page.insert_text((510,500),'01.1234',fontsize=8);self.assertEqual(s.reading(page)['eigen'],'01.1234')
 def test_conflicting_overprint_stays_unknown(self):
  page=self.page('Wapeningstekening');page.insert_text((510,500),'01.5678',fontsize=8);self.assertEqual(s.reading(page)['rol'],'?')
 def test_conflicting_role_headers_stay_unknown(self):self.assertEqual(s.reading(self.page('Vormtekening\nWapeningstekening'))['rol'],'?')
 def test_legend_w_does_not_assign_role(self):self.assertEqual(s.reading(self.page('W\nWapening B500B\nTotaal Elektra 0'))['rol'],'?')
 def test_outside_label_band_ignored(self):
  page=self.page(identity='GP123');page.insert_text((510,460),'01.9876');self.assertIsNone(s.reading(page))
 def test_malformed_and_multiple_hints_fail_closed(self):
  for text in ['LOCALCHAT_STAMP: bad W','LOCALCHAT_STAMP: 01.1234 W\nLOCALCHAT_STAMP: 01.5678 W','LOCALCHAT_STAMP: 01.1234 X']:self.assertEqual(s.classify(text),(True,None))
 def test_generic_info_and_originals_unchanged(self):
  page=self.page();original=page.get_text();self.assertTrue(extra.is_projecttekening('mixed.pdf',original)[0]);s.hint(page);self.assertEqual(page.get_text(),original)
 def test_hook_idempotence_and_preservation(self):
  original=(Path(args.package)/'mbs_webapp.py').read_bytes().decode('utf8');patched=hooks.patch(original);self.assertEqual(patched,hooks.patch(patched));ast.parse(patched);self.assertIn('def plan_wapening',patched)
 def test_bundle_reports_unknown_stamp_without_copy(self):
  with tempfile.TemporaryDirectory() as td:
   file=Path(td)/'bundle.pdf';page=self.page();doc=page.parent;doc.new_page().insert_text((30,30),'Voorblad\nProjectgegevens');doc.save(file)
   plan=web.plan_wapening(td,fase='bundel');unknown=[x for x in plan if x['soort']=='onbevestigd'];self.assertEqual(len(unknown),1);self.assertEqual(unknown[0]['pagina'],0);self.assertFalse(any(x.get('doel','').endswith('G9999_W.pdf') for x in plan))
 def test_ui_unknown_is_manual_not_missing_W(self):
  import time
  with tempfile.TemporaryDirectory() as td:
   page=self.page();d=page.parent;d.new_page().insert_text((30,30),'Voorblad\nProjectgegevens');d.save(Path(td)/'bundle.pdf')
   original=web.INPUT_DIR;web.INPUT_DIR=td
   try:
    web.start_wapening(extract=True)
    deadline=time.monotonic()+10
    while web._toestand['bezig'] and time.monotonic()<deadline:time.sleep(.02)
    self.assertFalse(web._toestand['bezig']);log=' '.join(str(x) for x in web._toestand['log']);self.assertIn('Handmatige controle:',log);self.assertNotIn('geen _W-bron',log)
   finally:web.INPUT_DIR=original
if __name__=='__main__':unittest.main(argv=[sys.argv[0]],verbosity=2)
