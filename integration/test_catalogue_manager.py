import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import sys
sys.path.insert(0,str(Path(__file__).resolve().parent))
from test_catalogue_update import xlsx,up,reader
spec=importlib.util.spec_from_file_location('seed',Path(__file__).with_name('seed_catalogues.py'));seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)
class CatalogueManagerTests(unittest.TestCase):
 def test_first_lelystad_source_and_previous_archived(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'first.xlsx';target=root/'data/catalogus_lelystad.json';xlsx(source,[['MBS1','Fixture','m','Active']]);before=source.read_bytes();preview,_=up.update(source,'lelystad',target,reader)
   self.assertFalse(target.exists());self.assertEqual(preview['target_sha256'],'missing')
   result,_=up.update(source,'lelystad',target,reader,True,False,preview['source_sha256'],preview['target_sha256']);self.assertTrue(result['applied']);self.assertEqual(source.read_bytes(),before);self.assertEqual(json.loads(target.read_text())['factory'],'lelystad');self.assertTrue(Path(result['source_archive']).is_file())
 def test_changed_source_or_snapshot_requires_new_preview(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source.xlsx';target=root/'catalogus_soest.json';xlsx(source,[['MBS1','Fixture','st','Active']]);preview,_=up.update(source,'soest',target,reader)
   xlsx(source,[['MBS2','Changed','st','Active']])
   with self.assertRaisesRegex(ValueError,'Source changed since preview'):up.update(source,'soest',target,reader,True,False,preview['source_sha256'],preview['target_sha256'])
   self.assertFalse(target.exists());preview,_=up.update(source,'soest',target,reader);up.update(source,'soest',target,reader,True)
   before=target.read_bytes()
   with self.assertRaisesRegex(ValueError,'Active catalogue changed since preview'):up.update(source,'soest',target,reader,True,False,preview['source_sha256'],preview['target_sha256'])
   self.assertEqual(target.read_bytes(),before)
 def test_registry_failure_rolls_back_both_files_exactly(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source.xlsx';target=root/'catalogus_veenoord.json';registry=root/'catalogues_registry.json';xlsx(source,[['MBS1','Old','st','Active']]);up.update(source,'veenoord',target,reader,True);before=target.read_bytes();registry_before=registry.read_bytes();xlsx(source,[['MBS1','New','m','Active']]);original=up.atomic
   def fail_registry(path,data):
    if Path(path).resolve()==registry.resolve():raise OSError('synthetic write failure')
    return original(path,data)
   with patch.object(up,'atomic',side_effect=fail_registry):
    with self.assertRaises(OSError):up.update(source,'veenoord',target,reader,True)
   self.assertEqual(target.read_bytes(),before);self.assertEqual(registry.read_bytes(),registry_before);up.update(source,'veenoord',target,reader,True)
 def test_concurrent_update_lock_and_release(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source.xlsx';target=root/'catalogus_alphen.json';xlsx(source,[['MBS1','Fixture','st','Active']])
   with up.update_lock(root):
    with self.assertRaisesRegex(ValueError,'Another catalogue import'):up.update(source,'alphen',target,reader,True)
   self.assertFalse(target.exists());up.update(source,'alphen',target,reader,True);self.assertTrue(target.exists())
 def test_empty_or_billing_only_never_activates(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);source=root/'source.xlsx';target=root/'catalogus_lelystad.json';xlsx(source,[['MB1','Billing','st','Active']])
   with self.assertRaisesRegex(ValueError,'no MBS'):up.update(source,'lelystad',target,reader,True)
   self.assertFalse(target.exists())
 def test_build_seed_preserves_user_catalogue_registry_and_archives(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);src=root/'build';dst=root/'runtime';src.mkdir();dst.mkdir();name='catalogus_soest.json';(src/name).write_text(json.dumps({'factory':'soest','records':[{'old':'source version'}]}));(dst/name).write_text(json.dumps({'factory':'soest','records':[{'new':'user import'}]}));registry=dst/'catalogues_registry.json';registry.write_bytes(b'user registry');archive=dst/'catalogue_sources/lelystad/new.xlsx';archive.parent.mkdir(parents=True);archive.write_bytes(b'user original');before={p:p.read_bytes() for p in (dst/name,registry,archive)}
   for factory in ('alphen','veenoord'):(src/('catalogus_'+factory+'.json')).write_text(json.dumps({'factory':factory,'records':[]}))
   result=seed.seed(src,dst);self.assertIn('soest',result['preserved']);self.assertEqual(set(result['initialized']),{'alphen','veenoord'});self.assertFalse((dst/'catalogus_lelystad.json').exists());self.assertTrue(all(p.read_bytes()==v for p,v in before.items()))
   self.assertFalse(seed.seed(src,dst)['initialized'])
if __name__=='__main__':unittest.main(verbosity=2)
