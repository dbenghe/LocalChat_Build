"""Factory presence is independent of spelling and drawing geometry; synthetic fixtures."""
from pathlib import Path
import ast,importlib.util,sys,unittest,argparse
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--package',default=str(ROOT/'pdfcontrol'));args,rest=parser.parse_known_args();sys.argv=[sys.argv[0]]+rest;PACKAGE=Path(args.package);sys.path.insert(0,str(ROOT/'integration'));sys.path.insert(0,str(PACKAGE))
import mbs_artikelcatalogus as reader
from mbs_factory_catalogue import article_finding,UNAVAILABLE_TEXT
from apply_factory_catalogue import patch

def record(code,name,unit='st'):return {'code':code,'description':name,'category':'import','fields':{'eenheid':unit}}
class FactoryCatalogueTests(unittest.TestCase):
 def setUp(self):self.cat=reader.Catalogus({'factory':'alphen','records':[record('A','Stekanker M24x705 (Ø20) EV'),record('B','T-FIXX M12x70 EV'),record('C','Boutanker M16x140 EV')]})
 def test_changed_length_is_missing_not_a_spelling_correction(self):
  finding=article_finding({'omschrijving':'Stekanker M24x715 (Ø20) EV'},self.cat);self.assertEqual(finding['code'],'ARTIKEL_NIET_IN_FABRIEKSCATALOGUS');self.assertIn('715',finding['tekst']);self.assertNotIn('705',finding['tekst'])
 def test_different_coating_stays_missing(self):self.assertIsNotNone(article_finding({'omschrijving':'T-FIXX M12x70 GV'},self.cat))
 def test_exact_known_article_and_combined_name_remain_valid(self):
  for name in ['Boutanker M16x140 EV','Boutanker M16x140 EV Boutanker M16x140 EV 150','stekanker M24×705 (Ø20) EV']:
   self.assertIsNone(article_finding({'omschrijving':name},self.cat),name)
 def test_conflicting_codes_remain_unconfirmed(self):
  cat=reader.Catalogus({'factory':'alphen','records':[record('A','Fixture'),record('B','Fixture')]});self.assertEqual(article_finding({'omschrijving':'Fixture'},cat)['code'],'ARTIKEL_FABRIEKSCATALOGUS_ONBEVESTIGD')
 def test_missing_catalogue_is_not_falsely_a_missing_article(self):self.assertIsNone(article_finding({'omschrijving':'Fixture'},None));self.assertIn('niet gecontroleerd',UNAVAILABLE_TEXT)
 def test_hooks_idempotent_and_preserve_side_identity(self):
  text=(PACKAGE/'mbs_controles_extra.py').read_text(encoding='utf8');self.assertEqual(patch(text),patch(patch(text)));ast.parse(patch(text));self.assertIn('ARTIKEL_NIET_IN_FABRIEKSCATALOGUS',(ROOT/'integration/factory_catalogue_findings.py').read_text())
 def test_spacing_keeps_numbers_and_cannot_join_numeric_fields(self):
  cat=reader.Catalogus({'factory':'alphen','records':[record('A','PVC elektrabuis Ø19'),record('B','Fixture 705')]})
  self.assertEqual(cat.zoek('PVC elektrabuisØ19','alphen')['status'],'catalogus_uniek')
  self.assertEqual(cat.zoek('Fixture 7 05','alphen')['status'],'niet_gevonden')
 def test_gain_retains_size_suffix_without_approving_equivalence(self):
  cat=reader.Catalogus({'factory':'alphen','records':[record('A','Gain Ø51')]})
  result=cat.zoek('Gain Ø51-650','alphen');self.assertEqual(result['status'],'catalogus_basisnaam');self.assertEqual(result['tekeninggegevens'],'-650');self.assertFalse(result['physical_equivalence_verified']);self.assertEqual(cat.zoek('Gain Ø510-650','alphen')['status'],'niet_gevonden')
 def test_only_explicit_sparing_shapes_are_classified(self):
  cat=reader.Catalogus({'factory':'alphen','records':[record('A','Sparing rond'),record('B','Sparing rechthoek')]})
  for name,code in [('sparing D225x200mm','A'),('sparing 100*300x200mm','B')]:self.assertEqual(cat.zoek(name,'alphen')['matches'][0]['code'],code)
  self.assertEqual(cat.zoek('sparing 100x200','alphen')['status'],'niet_gevonden')
 def test_native_page_explicitly_reports_unavailable_catalogue(self):
  import pymupdf,mbs_controles_extra as ex
  from unittest.mock import patch as mockpatch
  with pymupdf.open() as document:
   page=document.new_page();page.insert_text((60,80),'Vormtekening fixture')
   row={'pos':'B003','omschrijving':'Stekanker M24x715 (Ø20) EV','aantal':'2','aantal_n':2,'rect':pymupdf.Rect(20,700,300,720)}
   with mockpatch.object(ex,'lees_artikel_tabel',return_value=([row],pymupdf.Rect(0,690,500,800))),mockpatch.object(reader,'catalogus_voor_project',return_value=None):
    findings,rows=ex.extra_controles(page,'123456_fixture_V.pdf')
   self.assertTrue(any(f.code=='FABRIEKSCATALOGUS_NIET_GECONTROLEERD' for f in findings))
 def test_real_geometric_pipeline_keeps_independent_catalogue_warning(self):
  import pymupdf,mbs_controles_extra as ex
  with pymupdf.open() as doc:
   page=doc.new_page();page.insert_text((80,120),'B003 Stekanker M24x715 (Ø20) EV');row={'pos':'B003','omschrijving':'Stekanker M24x715 (Ø20) EV','aantal':'2','aantal_n':2,'rect':pymupdf.Rect(20,700,300,720)}
   findings,status=ex.controle_maatketens(page,[row],pymupdf.Rect(0,690,500,800),ex.tekstwoorden(page),catalogus=self.cat)
   self.assertEqual(sum(f.code=='ARTIKEL_NIET_IN_FABRIEKSCATALOGUS' for f in findings),1)
if __name__=='__main__':unittest.main(verbosity=2)
